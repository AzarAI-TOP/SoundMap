# SoundMap — Spatial Audio Demo Player

**Date:** 2026-06-23
**Status:** Approved design, pending implementation plan

## 1. Background & Motivation

SoundMap originally tried to apply a system-wide spatial effect to *all other apps'*
audio by attaching `Virtualizer` / `PresetReverb` / `DynamicsProcessing` to audio
session 0. On-device testing (Xiaomi / HyperOS, Android 16) proved this is fundamentally
impossible for a non-root, non-privileged app:

- **session 0** is owned by the OEM's Dolby DAP + MiSound effects and is bypassed entirely
  on Bluetooth A2DP (confirmed via `dumpsys media.audio_flinger`).
- **per-session attachment** is blocked because `getActivePlaybackConfigurations()` returns
  *anonymized* configs (sessionId = 0) to ordinary apps. The only permission that
  un-anonymizes them, `MODIFY_AUDIO_ROUTING`, is role-managed and refused by `pm grant`
  on Android 16 (`Permission ... is managed by role`). `DUMP` does not help.

The only non-root path that genuinely works system-wide (RootlessJamesDSP's internal audio
capture) requires Shizuku/ADB setup every boot and fails on DRM apps. The user chose instead
to **pivot the product**: SoundMap becomes a self-contained **spatial audio demo player**.
Because the audio is rendered by our own `AudioTrack`, the spatial effect is baked into the
PCM samples and therefore works reliably on any output, including Bluetooth, with **zero
special permissions**.

## 2. Goals / Non-Goals

**Goals**
- Play a small set of built-in demo sounds and spatialize them in real time around the
  listener's head, controlled by the existing clock dial (angle = direction, radius = distance).
- Convincing binaural realism via a structural binaural DSP model (ITD + ILD + head shadow +
  front/back cue + distance reverb), no external HRIR datasets.
- Rock-solid on Bluetooth; no permissions beyond notifications + a media-playback foreground
  service.

**Non-Goals (YAGNI for now)**
- Spatializing other apps' audio (proven impossible non-root on this device).
- Importing user audio files (procedural demo sources only for v1).
- Head tracking, multiple simultaneous sources, elevation (dial is the horizontal plane only).

## 3. User-Facing Behavior

- **Clock dial** (kept): drag the marker to position the sound; center = listener.
  - angle 0°=front, 90°=right, 180°=back, 270°=left; radius 0=at head, 1=far.
- **Center toggle** (repurposed): play / stop the selected demo sound. While playing it loops.
- **Dropdown at bottom** (new): selects which demo sound plays. Changing it while playing
  swaps the source seamlessly.
- Status readout: shows current direction/distance and play state.

## 4. Demo Sources (procedural, no bundled binaries)

Synthesized broadband/transient sounds localize best and are deterministic + unit-testable.
v1 set (mono, 48 kHz, float):

1. **Beep** — periodic short sine-tone bursts (e.g. 1 kHz, 120 ms on / 380 ms off).
2. **Noise pulses** — short white-noise bursts on the same cadence.
3. **Click train** — periodic broadband clicks (~6/s); strongest localization cue.

Each is a `DemoSource` that yields mono PCM frames on demand and loops indefinitely. White
noise uses a seeded PRNG so output is deterministic for tests.

## 5. Binaural DSP Model

Mono input → stereo output, parameters derived from `DialPosition`:

- **ITD (interaural time difference):** delay the far ear. Max ≈ 0.66 ms via the Woodworth
  approximation `ITD = (a/c)(θ + sinθ)`, head radius `a≈0.0875 m`, `c=343 m/s`. Implemented
  with a per-ear fractional delay line (linear interpolation).
- **ILD + head shadow:** attenuate the far ear (level) and apply a one-pole low-pass to it
  whose cutoff falls as |angle| grows (high frequencies are shadowed by the head).
- **Front/back cue:** one-pole spectral tilt approximating pinna filtering — front = brighter,
  back = duller. Resolves the front/back ambiguity that pure ITD/ILD leaves.
- **Distance:** overall gain rolloff with radius, plus a compact mono Schroeder reverb
  (4 comb + 2 all-pass) whose wet mix rises with radius for a sense of depth.
- **Smoothing:** all parameters (gains, delay lengths, cutoffs, wet) are interpolated
  per-block toward targets to avoid zipper noise when the dial moves.

## 6. Architecture & Components

Single source of truth in the app process; UI and foreground service both go through the engine.

| Component | Responsibility | Dependencies | Testable |
|---|---|---|---|
| `DemoSource` (interface) + `BeepSource`, `NoiseSource`, `ClickTrainSource` | Produce mono PCM frames, loop | none (pure) | Yes — deterministic samples |
| `BinauralParams` + `BinauralMapping.map(DialPosition)` | Map dial → {itdSeconds, leftGain, rightGain, farEarCutoff, frontBackTilt, distanceGain, reverbWet} | none (pure) | Yes — pure |
| `BinauralProcessor` | Stateful DSP: mono block + params → stereo block (delay lines, filters, reverb) | none (pure Kotlin/JVM math) | Yes — impulse/energy assertions |
| `BinauralPlayer` | Owns `AudioTrack` (ENCODING_PCM_FLOAT, stereo, 48 kHz) + a `URGENT_AUDIO` thread; loop: pull `DemoSource` → `BinauralProcessor` → write | Android `AudioTrack` | Device verification |
| `SoundMapEngine` | Control layer: `play(sourceId)`, `stop()`, `setPosition(pos)`, `setSource(id)`; exposes `EngineState` `StateFlow` | `BinauralPlayer` | Light |
| `SoundMapViewModel` | UI bridge; persists last source + position + (optionally) play state | engine, `SettingsStore` | — |
| UI (`SoundMapScreen`, `ClockDial`, new source dropdown) | Dial + toggle + dropdown | engine state | — |
| `SoundMapService` | Foreground service, now `mediaPlayback` type, keeps playback alive in background | engine | — |

**Removed:** `SessionEffectChain`, `SessionEffectManager`, `SpatialAudioController` (already
gone), session-0 logic, `DUMP` permission, and the old `SpatialMapping`/`EffectParams`
(replaced by `BinauralMapping`/`BinauralParams`). The `SpatialMappingTest` is replaced by
`BinauralMappingTest` + `BinauralProcessorTest`.

## 7. Data Flow

```
ClockDial drag ─▶ ViewModel.onPositionChange ─▶ Engine.setPosition ─▶ BinauralPlayer (target params)
center tap     ─▶ ViewModel.toggle          ─▶ Engine.play/stop    ─▶ BinauralPlayer start/stop thread
dropdown pick  ─▶ ViewModel.onSourceChange  ─▶ Engine.setSource    ─▶ BinauralPlayer swaps DemoSource
playback thread loop: DemoSource.read(block) ─▶ BinauralProcessor.process(block, params) ─▶ AudioTrack.write
Engine.EngineState (enabled/playing, position, sourceId) ─▶ StateFlow ─▶ UI
```

## 8. Threading & Audio Config

- AudioTrack: `ENCODING_PCM_FLOAT`, stereo, 48 kHz, `MODE_STREAM`, `USAGE_MEDIA` /
  `CONTENT_TYPE_MUSIC`, buffer sized from `getMinBufferSize` (a few × min for safety).
- One dedicated playback thread at `THREAD_PRIORITY_URGENT_AUDIO`; block size ~5–10 ms.
- Position/source updates are posted as targets read by the playback thread (no locks on the
  hot path beyond a volatile/atomic reference); the processor interpolates toward targets.

## 9. Error Handling

- `AudioTrack` init failure → engine reports a non-playing error state surfaced in the UI
  ("无法初始化音频输出"); no crash.
- Source decode/generation is synthetic and cannot fail at runtime; guard divide-by-zero in
  DSP (radius/angle edge cases) via clamping (mirrors existing `coerceIn` usage).
- Service death → engine stops playback (safety net, as today).

## 10. Persistence

`SettingsStore` extends to persist: last dial position (exists) and last selected `sourceId`.
Both are restored on launch. Playback state is **not** persisted and **never** auto-starts on
launch — the user must tap to play (avoids surprising audio on open). Saves stay debounced as
today.

## 11. Testing Strategy

- **`BinauralMappingTest`** (pure): right source → right ear earlier (negative/positive ITD
  sign) and louder; left mirror; front brighter tilt than back; far → lower distanceGain +
  higher reverbWet than near; params clamped at radius>1 / arbitrary angle.
- **`BinauralProcessorTest`** (pure): feed a unit impulse at angle 90° → right channel onset
  precedes left by ≈ITD samples and has higher peak/energy; at angle 270° the mirror; at far
  radius overall energy lower and a non-zero reverb tail exists after the direct impulse.
- **`DemoSourceTest`** (pure): each source yields the expected frame counts, loops, and
  (noise) is deterministic under a fixed seed; bursts have on/off envelope where specified.
- **Device verification:** install, Bluetooth headset, play each demo, sweep the dial; capture
  logcat to confirm the playback thread runs without underruns.

## 12. Manifest / Permissions Changes

- Remove `android.permission.DUMP`.
- Foreground service type `specialUse` → `mediaPlayback`; add
  `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Keep `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`. `MODIFY_AUDIO_SETTINGS` no longer required (no global effects) — remove.

## 13. Open Questions / Future Work

- Upgrade structural binaural model to measured HRIR convolution if realism is insufficient.
- Optional: bundled real recordings (footsteps, helicopter) as additional sources.
- Optional: animated marker auto-orbit to showcase the effect hands-free.
