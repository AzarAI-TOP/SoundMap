# SoundMap

A spatial-audio demo player for Android. SoundMap synthesizes a few demo sounds
and renders them through a custom real-time **binaural DSP engine**, so the
spatial effect is baked directly into the audio samples. Because the processing
happens in-app before the audio leaves the device, the effect survives any
output path — wired headphones, the speaker, and **Bluetooth** alike — with
**zero special permissions** and no root.

Drag the sound around a clock-style dial to move it in direction and distance,
and hear it move around your head in real time.

> Use headphones (or earbuds) for the spatial effect. Speakers won't reproduce it.

## Why it works this way

The original goal was a system-wide effect applied to other apps' audio. On
non-rooted Android that is architecturally impossible: A2DP hardware offload
bypasses software effects, and the routing privileges required
(`MODIFY_AUDIO_ROUTING`) are role-managed and ungrantable to a normal app. So
SoundMap instead renders its **own** PCM with the spatial effect already mixed
in — which works everywhere, including Bluetooth, and needs no privileged access.

## Features

- **Custom binaural DSP**, computed per-sample in real time:
  - **ITD** — interaural time difference via a fractional delay line (Woodworth model, max 0.66 ms)
  - **ILD / head shadow** — per-ear gain plus a one-pole low-pass on the far ear
  - **Front/back tonal cue** — spectral tilt that distinguishes front from back
  - **Distance** — gain rolloff plus a Schroeder reverb (4 combs + 2 all-passes) for a sense of space
  - **Parameter smoothing** — per-sample interpolation so movement has no zipper noise
- **Three procedural demo sources** — beep (`滴声`), noise pulse (`噪声脉冲`), click train (`嗒嗒声`)
- **Clock dial UI** (Jetpack Compose) — drag for direction (angle) and distance (radius)
- **Foreground service** (`mediaPlayback`) keeps playback alive in the background
- **No special permissions** — just foreground-service + notifications

## Architecture

```
DemoSource ──▶ BinauralProcessor ──▶ BinauralPlayer ──▶ AudioTrack
(mono PCM)     (mono → stereo DSP)    (URGENT_AUDIO      (48 kHz, stereo,
                                       render thread)     PCM float)
```

- `audio/DemoSource.kt` — procedural mono sound generators
- `audio/BinauralParams.kt` — maps a dial position to DSP parameters (pure functions)
- `audio/BinauralProcessor.kt` — the stateful DSP: delay lines, filters, reverb
- `audio/BinauralPlayer.kt` — owns the `AudioTrack` and the render loop
- `audio/SoundMapEngine.kt` — process-wide state holder exposed to the UI
- `ui/` — Jetpack Compose screen, clock dial, theme
- `SoundMapViewModel.kt` / `SoundMapService.kt` — UI binding and foreground service

The pure pieces (`DemoSource`, `BinauralParams`, `BinauralProcessor`) are covered
by unit tests.

## Build & run

Requires **JDK 21** (the toolchain fails on newer JDKs).

```bash
# point JAVA_HOME at a JDK 21 install, e.g. via SDKMAN:
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-tem"

./gradlew assembleDebug      # build the APK
./gradlew test               # run unit tests
./gradlew installDebug       # install on a connected device
```

- **minSdk** 28 · **targetSdk** 36 · application id `top.azarai.soundmap`

## How to use

1. Put on headphones.
2. Pick a demo sound from the dropdown at the bottom (`嗒嗒声` / click is easiest to localize).
3. Tap the center of the dial to start/stop playback.
4. Drag the dot around the dial — toward the edge to push the sound farther away,
   around the rim to move it left/right and front/back.

## License

[MIT](LICENSE)
