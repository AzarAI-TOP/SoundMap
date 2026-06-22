# Spatial Audio Demo Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert SoundMap into a self-contained spatial-audio demo player that renders built-in demo sounds through a real binaural DSP engine, positioned by the clock dial — so the effect is baked into our own audio and works on any output including Bluetooth, with zero special permissions.

**Architecture:** A dedicated `AudioTrack` (float PCM, 48 kHz, stereo) is fed by a playback thread that pulls mono samples from a procedural `DemoSource`, runs them through a stateful `BinauralProcessor` (ITD + ILD/head-shadow + front/back tilt + distance reverb), and writes stereo out. Pure DSP/param/source code is unit-tested on the JVM; the Android `AudioTrack` glue is device-verified. `SoundMapEngine` is the single source of truth driving the UI (dial + play toggle + source dropdown).

**Tech Stack:** Kotlin, Android (`AudioTrack`, `ENCODING_PCM_FLOAT`), Jetpack Compose, Gradle (AGP, Compose), JUnit4.

## Global Constraints

- **Build with JDK 21**, never the machine-default JDK 25 (AGP/Gradle 8.13 reject 25 with a cryptic `25.0.3` error). Every gradle command in this plan is prefixed with:
  `export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"`
- **Always pass `--offline`** to every gradle invocation. The network/VPN is unreliable; all dependencies are already cached. If a command fails complaining about a missing dependency (not a network timeout), stop and report — do not drop `--offline`.
- **Android SDK** at `/home/azarai/Android/Sdk`; `platform-tools` (adb) lives there. `compileSdk = 36`, `minSdk = 28`, `targetSdk = 36`.
- **Package root:** `top.azarai.soundmap`. Source under `app/src/main/java/...`, tests under `app/src/test/java/...`.
- **Audio config (verbatim):** sample rate `48000`, stereo, `AudioFormat.ENCODING_PCM_FLOAT`, block size `480` frames (10 ms).
- **No new third-party dependencies.** Pure Kotlin math + existing AndroidX only.
- **Git:** this directory is NOT a git repo yet. If you want per-task commits, run `git init` once (Task 0); otherwise treat every `commit` step as an optional checkpoint and skip it.
- **DSP determinism:** all `DemoSource`, `BinauralMapping`, and `BinauralProcessor` code must be pure Kotlin with no Android imports, so it runs under plain JUnit.

## File Structure

**New (pure, JVM-testable):**
- `app/src/main/java/top/azarai/soundmap/audio/DemoSource.kt` — `DemoSourceId` enum, `DemoSource` interface, `create()` factory, three procedural sources (`BeepSource`, `NoiseSource`, `ClickTrainSource`).
- `app/src/main/java/top/azarai/soundmap/audio/BinauralParams.kt` — `BinauralParams` data class + `BinauralMapping` object (`DialPosition` → params).
- `app/src/main/java/top/azarai/soundmap/audio/BinauralProcessor.kt` — stateful DSP (delay lines, one-pole filters, Schroeder reverb) + private helper classes.

**New (Android, device-verified):**
- `app/src/main/java/top/azarai/soundmap/audio/BinauralPlayer.kt` — owns `AudioTrack` + playback thread.

**Rewritten:**
- `app/src/main/java/top/azarai/soundmap/audio/SoundMapEngine.kt` — control layer over `BinauralPlayer`; new `EngineState`.
- `app/src/main/java/top/azarai/soundmap/SoundMapViewModel.kt` — play/stop/position/source + persistence.
- `app/src/main/java/top/azarai/soundmap/ui/SoundMapScreen.kt` — status text + source dropdown.
- `app/src/main/java/top/azarai/soundmap/data/SettingsStore.kt` — persist `sourceId` instead of `enabled`.
- `app/src/main/java/top/azarai/soundmap/SoundMapService.kt` — `mediaPlayback` FGS type.
- `app/src/main/AndroidManifest.xml` — drop `DUMP` + `MODIFY_AUDIO_SETTINGS`, add `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, service type `mediaPlayback`.
- `app/src/main/res/values/strings.xml` — source labels + notification copy.

**New tests:**
- `app/src/test/java/top/azarai/soundmap/audio/DemoSourceTest.kt`
- `app/src/test/java/top/azarai/soundmap/audio/BinauralMappingTest.kt`
- `app/src/test/java/top/azarai/soundmap/audio/BinauralProcessorTest.kt`

**Deleted:**
- `audio/SpatialMapping.kt`, `audio/EffectParams.kt`, `audio/SessionEffectManager.kt`, `audio/SessionEffectChain.kt`, `test/.../audio/SpatialMappingTest.kt`. (`SpatialAudioController.kt` already removed.)

**Unchanged:** `audio/DialPosition.kt`, `ui/ClockDial.kt` (its `enabled` flag now means "playing"; `ON/OFF` label still reads fine), theme files, `MainActivity.kt`.

---

### Task 0 (optional): Initialize git for per-task commits

Skip entirely if you don't want git. If you do:

- [ ] **Step 1: Init and first commit**

```bash
cd /home/azarai/Workspace/SoundMap
git init
printf '%s\n' '.gradle/' '.kotlin/' 'build/' 'app/build/' 'local.properties' '*.log' > .gitignore
git add -A
git commit -m "chore: snapshot before demo-player rewrite"
```

---

### Task 1: Procedural demo sources

**Files:**
- Create: `app/src/main/java/top/azarai/soundmap/audio/DemoSource.kt`
- Test: `app/src/test/java/top/azarai/soundmap/audio/DemoSourceTest.kt`

**Interfaces:**
- Produces: `enum class DemoSourceId { BEEP, NOISE, CLICK }`; `interface DemoSource { fun read(out: FloatArray, n: Int); fun reset() }`; `fun DemoSourceId.create(sampleRate: Int): DemoSource`. `read` fills `out[0 until n]` with mono samples in `[-1f, 1f]`, advancing an internal sample counter and looping forever.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/top/azarai/soundmap/audio/DemoSourceTest.kt`:

```kotlin
package top.azarai.soundmap.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSourceTest {

    private val sr = 48000

    private fun FloatArray.maxAbs() = fold(0f) { m, x -> maxOf(m, kotlin.math.abs(x)) }

    @Test
    fun beep_isLoudDuringBurstAndSilentBetween() {
        val src = DemoSourceId.BEEP.create(sr)
        val buf = FloatArray(sr / 2) // exactly one 500ms cycle
        src.read(buf, buf.size)
        val onLen = (0.12f * sr).toInt()
        val onWindow = buf.copyOfRange(0, onLen)
        val offWindow = buf.copyOfRange(onLen, buf.size)
        assertTrue("burst should contain audio", onWindow.maxAbs() > 0.1f)
        assertEquals("gap should be silent", 0f, offWindow.maxAbs(), 0f)
    }

    @Test
    fun noise_isDeterministicForSameSeed() {
        val a = FloatArray(2000).also { DemoSourceId.NOISE.create(sr).read(it, it.size) }
        val b = FloatArray(2000).also { DemoSourceId.NOISE.create(sr).read(it, it.size) }
        assertTrue("two fresh noise sources must match sample-for-sample", a.contentEquals(b))
    }

    @Test
    fun clickTrain_hasImpulsesSeparatedBySilence() {
        val src = DemoSourceId.CLICK.create(sr)
        val buf = FloatArray(sr / 3) // ~2 click periods
        src.read(buf, buf.size)
        assertTrue("first click present", kotlin.math.abs(buf[0]) > 0.5f)
        val period = sr / 6
        val midGap = buf[period / 2]
        assertEquals("mid-gap is silent", 0f, midGap, 0f)
    }

    @Test
    fun read_loopsAcrossCalls() {
        val src = DemoSourceId.BEEP.create(sr)
        val first = FloatArray(sr / 2).also { src.read(it, it.size) }
        val second = FloatArray(sr / 2).also { src.read(it, it.size) }
        assertTrue("cycle repeats", first.contentEquals(second))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.DemoSourceTest" --offline
```
Expected: FAIL — `DemoSourceId` / `DemoSource` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/top/azarai/soundmap/audio/DemoSource.kt`:

```kotlin
package top.azarai.soundmap.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The selectable built-in demo sounds. */
enum class DemoSourceId { BEEP, NOISE, CLICK }

/** A looping mono PCM generator. Pure: no Android, deterministic, JVM-testable. */
interface DemoSource {
    /** Fill [out] `[0, n)` with the next mono samples in [-1, 1], looping forever. */
    fun read(out: FloatArray, n: Int)
    /** Rewind to the start of the loop. */
    fun reset()
}

fun DemoSourceId.create(sampleRate: Int): DemoSource = when (this) {
    DemoSourceId.BEEP -> BeepSource(sampleRate)
    DemoSourceId.NOISE -> NoiseSource(sampleRate)
    DemoSourceId.CLICK -> ClickTrainSource(sampleRate)
}

private const val CYCLE_SEC = 0.5f
private const val BURST_SEC = 0.12f
private const val FADE_SEC = 0.005f

/** A 1 kHz tone burst, 120 ms on / 380 ms off, with short fades to avoid clicks. */
internal class BeepSource(private val sampleRate: Int, private val freq: Float = 1000f) : DemoSource {
    private val period = (CYCLE_SEC * sampleRate).toLong()
    private val onLen = (BURST_SEC * sampleRate).toLong()
    private val fade = (FADE_SEC * sampleRate).toLong().coerceAtLeast(1)
    private var t = 0L

    override fun read(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val ph = t % period
            out[i] = if (ph < onLen) {
                val env = minOf(ph, onLen - ph, fade).toFloat() / fade
                (sin(2.0 * PI * freq * t / sampleRate).toFloat()) * env
            } else 0f
            t++
        }
    }

    override fun reset() { t = 0L }
}

/** White-noise bursts on the same cadence as [BeepSource]. Seeded for determinism. */
internal class NoiseSource(private val sampleRate: Int, private val seed: Long = 1L) : DemoSource {
    private val period = (CYCLE_SEC * sampleRate).toLong()
    private val onLen = (BURST_SEC * sampleRate).toLong()
    private var t = 0L
    private var rng = Random(seed)

    override fun read(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val ph = t % period
            out[i] = if (ph < onLen) (rng.nextFloat() * 2f - 1f) * 0.7f else 0f
            t++
        }
    }

    override fun reset() { t = 0L; rng = Random(seed) }
}

/** A broadband click train (~6 clicks/sec); each click is a short exponential decay. */
internal class ClickTrainSource(private val sampleRate: Int) : DemoSource {
    private val period = (sampleRate / 6).toLong()
    private val tau = 0.0015f * sampleRate // ~1.5 ms decay
    private var t = 0L

    override fun read(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val ph = (t % period).toFloat()
            out[i] = if (ph < tau * 6f) exp(-ph / tau) else 0f
            t++
        }
    }

    override fun reset() { t = 0L }
}
```

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.DemoSourceTest" --offline
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit (optional)**

```bash
git add app/src/main/java/top/azarai/soundmap/audio/DemoSource.kt app/src/test/java/top/azarai/soundmap/audio/DemoSourceTest.kt
git commit -m "feat: procedural demo sound sources"
```

---

### Task 2: Binaural parameter mapping

**Files:**
- Create: `app/src/main/java/top/azarai/soundmap/audio/BinauralParams.kt`
- Test: `app/src/test/java/top/azarai/soundmap/audio/BinauralMappingTest.kt`

**Interfaces:**
- Consumes: `DialPosition(angleDeg, radius)` from `audio/DialPosition.kt` (angle 0°=front, 90°=right, 180°=back, 270°=left; radius 0..1).
- Produces:
  ```kotlin
  data class BinauralParams(
      val leftDelaySamples: Float, val rightDelaySamples: Float,
      val leftGain: Float, val rightGain: Float,
      val leftCutoffHz: Float, val rightCutoffHz: Float,
      val toneCutoffHz: Float, val distanceGain: Float, val reverbWet: Float,
  )
  object BinauralMapping { fun map(position: DialPosition, sampleRate: Int): BinauralParams; /* + public const tuning */ }
  ```
  Convention: the **far** ear (opposite the source direction) is the one delayed, attenuated, and low-passed.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/top/azarai/soundmap/audio/BinauralMappingTest.kt`:

```kotlin
package top.azarai.soundmap.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinauralMappingTest {

    private val sr = 48000

    @Test
    fun center_isNeutral() {
        val p = BinauralMapping.map(DialPosition.CENTER, sr)
        assertEquals(0f, p.leftDelaySamples, 1e-4f)
        assertEquals(0f, p.rightDelaySamples, 1e-4f)
        assertEquals(p.leftGain, p.rightGain, 1e-4f)
        assertEquals(0f, p.reverbWet, 1e-4f)
        assertEquals(1f, p.distanceGain, 1e-4f)
    }

    @Test
    fun right_delaysAndDampsLeftEar() {
        val p = BinauralMapping.map(DialPosition(angleDeg = 90f, radius = 0.5f), sr)
        assertTrue("left (far) ear delayed", p.leftDelaySamples > 0f)
        assertEquals("right (near) ear not delayed", 0f, p.rightDelaySamples, 1e-4f)
        assertTrue("left ear quieter", p.leftGain < p.rightGain)
        assertTrue("left ear more low-passed", p.leftCutoffHz < p.rightCutoffHz)
    }

    @Test
    fun left_isMirrorOfRight() {
        val p = BinauralMapping.map(DialPosition(angleDeg = 270f, radius = 0.5f), sr)
        assertTrue(p.rightDelaySamples > 0f)
        assertEquals(0f, p.leftDelaySamples, 1e-4f)
        assertTrue(p.rightGain < p.leftGain)
    }

    @Test
    fun front_isBrighterThanBack() {
        val front = BinauralMapping.map(DialPosition(angleDeg = 0f, radius = 0.5f), sr)
        val back = BinauralMapping.map(DialPosition(angleDeg = 180f, radius = 0.5f), sr)
        assertTrue("front tone is brighter", front.toneCutoffHz > back.toneCutoffHz)
    }

    @Test
    fun farther_isQuieterWithMoreReverb() {
        val near = BinauralMapping.map(DialPosition(angleDeg = 0f, radius = 0.1f), sr)
        val far = BinauralMapping.map(DialPosition(angleDeg = 0f, radius = 1f), sr)
        assertTrue(far.distanceGain < near.distanceGain)
        assertTrue(far.reverbWet > near.reverbWet)
    }

    @Test
    fun radius_isClamped() {
        val p = BinauralMapping.map(DialPosition(angleDeg = 45f, radius = 5f), sr)
        assertTrue(p.reverbWet <= BinauralMapping.MAX_WET + 1e-4f)
        assertTrue(p.distanceGain in 0f..1f)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.BinauralMappingTest" --offline
```
Expected: FAIL — `BinauralMapping` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/top/azarai/soundmap/audio/BinauralParams.kt`:

```kotlin
package top.azarai.soundmap.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Per-sample targets for [BinauralProcessor], derived from a [DialPosition].
 * Pure data — no Android — so the mapping stays JVM-testable.
 */
data class BinauralParams(
    val leftDelaySamples: Float,
    val rightDelaySamples: Float,
    val leftGain: Float,
    val rightGain: Float,
    val leftCutoffHz: Float,
    val rightCutoffHz: Float,
    val toneCutoffHz: Float,
    val distanceGain: Float,
    val reverbWet: Float,
)

/**
 * Structural binaural model: lateral angle -> ITD + ILD/head-shadow on the far ear;
 * front/back -> a tonal brightness cue; distance -> level rolloff + reverb send.
 */
object BinauralMapping {
    const val MAX_ITD_SEC = 0.00066f          // Woodworth max interaural time difference
    const val MAX_ILD_DB = 8f                 // far-ear level drop at full lateral
    const val NEAR_CUTOFF_HZ = 18000f         // near ear ~ full band
    const val FAR_CUTOFF_MIN_HZ = 2500f       // far ear at full lateral
    const val TONE_BACK_HZ = 3000f            // dull when behind
    const val TONE_FRONT_HZ = 16000f          // bright when in front
    const val DIST_K = 1.0f                   // distance attenuation strength
    const val MAX_WET = 0.4f                  // reverb send at the far edge

    fun map(position: DialPosition, sampleRate: Int): BinauralParams {
        val radius = position.radius.coerceIn(0f, 1f)
        val theta = Math.toRadians(position.angleDeg.toDouble())
        val lateral = sin(theta).toFloat()    // +right .. -left
        val frontness = cos(theta).toFloat()  // +front .. -back
        val shadow = abs(lateral)

        val itd = MAX_ITD_SEC * shadow * sampleRate
        val farGain = 10f.pow(-MAX_ILD_DB * shadow / 20f)
        val farCutoff = lerp(NEAR_CUTOFF_HZ, FAR_CUTOFF_MIN_HZ, shadow)

        // Decide which ear is far (delayed/damped). lateral >= 0 -> source on right -> left far.
        val leftDelay: Float; val rightDelay: Float
        val leftGain: Float; val rightGain: Float
        val leftCut: Float; val rightCut: Float
        if (lateral >= 0f) {
            leftDelay = itd; rightDelay = 0f
            leftGain = farGain; rightGain = 1f
            leftCut = farCutoff; rightCut = NEAR_CUTOFF_HZ
        } else {
            leftDelay = 0f; rightDelay = itd
            leftGain = 1f; rightGain = farGain
            leftCut = NEAR_CUTOFF_HZ; rightCut = farCutoff
        }

        return BinauralParams(
            leftDelaySamples = leftDelay,
            rightDelaySamples = rightDelay,
            leftGain = leftGain,
            rightGain = rightGain,
            leftCutoffHz = leftCut,
            rightCutoffHz = rightCut,
            toneCutoffHz = lerp(TONE_BACK_HZ, TONE_FRONT_HZ, (frontness + 1f) / 2f),
            distanceGain = 1f / (1f + DIST_K * radius),
            reverbWet = radius * MAX_WET,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}
```

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.BinauralMappingTest" --offline
```
Expected: PASS (6 tests).

- [ ] **Step 5: Commit (optional)**

```bash
git add app/src/main/java/top/azarai/soundmap/audio/BinauralParams.kt app/src/test/java/top/azarai/soundmap/audio/BinauralMappingTest.kt
git commit -m "feat: binaural parameter mapping"
```

---

### Task 3: Binaural DSP processor

**Files:**
- Create: `app/src/main/java/top/azarai/soundmap/audio/BinauralProcessor.kt`
- Test: `app/src/test/java/top/azarai/soundmap/audio/BinauralProcessorTest.kt`

**Interfaces:**
- Consumes: `BinauralParams`, `BinauralMapping` (Task 2).
- Produces:
  ```kotlin
  class BinauralProcessor(sampleRate: Int) {
      fun setTarget(p: BinauralParams, snap: Boolean = false)   // snap=true jumps instantly (no smoothing)
      fun process(mono: FloatArray, n: Int, outStereo: FloatArray) // outStereo length >= 2*n, interleaved L,R
  }
  ```
  Smoothing interpolates current params toward target each sample; `snap` is used on start and in tests for determinism.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/top/azarai/soundmap/audio/BinauralProcessorTest.kt`:

```kotlin
package top.azarai.soundmap.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BinauralProcessorTest {

    private val sr = 48000
    private val n = 512

    private fun impulse() = FloatArray(n).also { it[0] = 1f }

    private fun process(angle: Float, radius: Float): FloatArray {
        val proc = BinauralProcessor(sr)
        proc.setTarget(BinauralMapping.map(DialPosition(angle, radius), sr), snap = true)
        val out = FloatArray(2 * n)
        proc.process(impulse(), n, out)
        return out
    }

    private fun channel(out: FloatArray, right: Boolean): FloatArray =
        FloatArray(n) { out[2 * it + if (right) 1 else 0] }

    private fun argMax(x: FloatArray): Int {
        var bi = 0; var bv = -1f
        for (i in x.indices) { val v = abs(x[i]); if (v > bv) { bv = v; bi = i } }
        return bi
    }

    private fun energy(x: FloatArray) = x.fold(0f) { s, v -> s + v * v }

    @Test
    fun rightSource_reachesRightEarFirstAndLouder() {
        val out = process(angle = 90f, radius = 0.5f)
        val left = channel(out, right = false)
        val right = channel(out, right = true)
        assertTrue("right ear onset earlier than left", argMax(right) < argMax(left))
        assertTrue("right ear carries more energy", energy(right) > energy(left))
    }

    @Test
    fun leftSource_isMirror() {
        val out = process(angle = 270f, radius = 0.5f)
        val left = channel(out, right = false)
        val right = channel(out, right = true)
        assertTrue(argMax(left) < argMax(right))
        assertTrue(energy(left) > energy(right))
    }

    @Test
    fun distance_addsReverbTail() {
        val far = process(angle = 0f, radius = 1f)
        val near = process(angle = 0f, radius = 0f)
        // Tail well after the direct impulse (and any ITD): reverb should ring for "far".
        fun tail(out: FloatArray): Float {
            var s = 0f
            for (k in 200 until n) { s += abs(out[2 * k]) + abs(out[2 * k + 1]) }
            return s
        }
        assertTrue("far has audible reverb tail", tail(far) > 1e-3f)
        assertTrue("far tail exceeds near tail", tail(far) > tail(near))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.BinauralProcessorTest" --offline
```
Expected: FAIL — `BinauralProcessor` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/top/azarai/soundmap/audio/BinauralProcessor.kt`:

```kotlin
package top.azarai.soundmap.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor

/**
 * Stateful real-time binaural renderer: mono in -> stereo out, shaped by [BinauralParams].
 * Pure Kotlin (no Android) so it runs under JUnit. Not thread-safe; the playback thread
 * owns one instance and may call [setTarget] from another thread (target is @Volatile).
 */
class BinauralProcessor(private val sampleRate: Int) {

    private val maxDelay = (BinauralMapping.MAX_ITD_SEC * sampleRate).toInt() + 2
    private val delayL = DelayLine(maxDelay)
    private val delayR = DelayLine(maxDelay)
    private val shadowL = OnePoleLP()
    private val shadowR = OnePoleLP()
    private val toneLP = OnePoleLP()
    private val reverb = Reverb(sampleRate)

    @Volatile private var target = BinauralMapping.map(DialPosition.CENTER, sampleRate)

    // Smoothed current values.
    private var cLeftDelay = 0f
    private var cRightDelay = 0f
    private var cLeftGain = 1f
    private var cRightGain = 1f
    private var cLeftCut = BinauralMapping.NEAR_CUTOFF_HZ
    private var cRightCut = BinauralMapping.NEAR_CUTOFF_HZ
    private var cTone = BinauralMapping.TONE_FRONT_HZ
    private var cDist = 1f
    private var cWet = 0f

    fun setTarget(p: BinauralParams, snap: Boolean = false) {
        target = p
        if (snap) {
            cLeftDelay = p.leftDelaySamples; cRightDelay = p.rightDelaySamples
            cLeftGain = p.leftGain; cRightGain = p.rightGain
            cLeftCut = p.leftCutoffHz; cRightCut = p.rightCutoffHz
            cTone = p.toneCutoffHz; cDist = p.distanceGain; cWet = p.reverbWet
        }
    }

    fun process(mono: FloatArray, n: Int, outStereo: FloatArray) {
        val p = target
        for (k in 0 until n) {
            cLeftDelay += (p.leftDelaySamples - cLeftDelay) * SMOOTH
            cRightDelay += (p.rightDelaySamples - cRightDelay) * SMOOTH
            cLeftGain += (p.leftGain - cLeftGain) * SMOOTH
            cRightGain += (p.rightGain - cRightGain) * SMOOTH
            cLeftCut += (p.leftCutoffHz - cLeftCut) * SMOOTH
            cRightCut += (p.rightCutoffHz - cRightCut) * SMOOTH
            cTone += (p.toneCutoffHz - cTone) * SMOOTH
            cDist += (p.distanceGain - cDist) * SMOOTH
            cWet += (p.reverbWet - cWet) * SMOOTH

            val toned = toneLP.process(mono[k], coef(cTone))
            delayL.push(toned)
            delayR.push(toned)

            val l = shadowL.process(delayL.read(cLeftDelay), coef(cLeftCut)) * cLeftGain
            val r = shadowR.process(delayR.read(cRightDelay), coef(cRightCut)) * cRightGain
            val wet = reverb.process(toned) * cWet

            outStereo[2 * k] = cDist * l + wet
            outStereo[2 * k + 1] = cDist * r + wet
        }
    }

    /** One-pole low-pass coefficient for a given cutoff. */
    private fun coef(fc: Float): Float =
        exp(-2.0 * PI * fc / sampleRate).toFloat().coerceIn(0f, 0.9999f)

    private companion object {
        // ~ first-order smoothing toward target; small => gentle, click-free param moves.
        const val SMOOTH = 0.0015f
    }
}

/** Circular buffer with fractional (linearly interpolated) read. */
private class DelayLine(maxDelaySamples: Int) {
    private val buf = FloatArray(maxDelaySamples + 4)
    private var w = 0

    fun push(x: Float) {
        w = (w + 1) % buf.size
        buf[w] = x
    }

    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(0f, (buf.size - 2).toFloat())
        val i = floor(d).toInt()
        val frac = d - i
        val i0 = (w - i + buf.size) % buf.size
        val i1 = (i0 - 1 + buf.size) % buf.size
        return buf[i0] * (1f - frac) + buf[i1] * frac
    }
}

/** y[n] = (1-a)x[n] + a*y[n-1]. */
private class OnePoleLP {
    private var y = 0f
    fun process(x: Float, a: Float): Float {
        y = (1f - a) * x + a * y
        return y
    }
}

/** Lightweight Schroeder reverb: 4 damped feedback combs in parallel, 2 all-passes in series. */
private class Reverb(sampleRate: Int) {
    private fun scale(n: Int) = (n.toLong() * sampleRate / 44100).toInt().coerceAtLeast(1)
    private val combs = intArrayOf(1116, 1277, 1422, 1557).map { Comb(scale(it), 0.84f, 0.2f) }
    private val allpasses = intArrayOf(556, 341).map { Allpass(scale(it), 0.5f) }

    fun process(x: Float): Float {
        var y = 0f
        for (c in combs) y += c.process(x)
        y /= combs.size
        for (a in allpasses) y = a.process(y)
        return y
    }
}

private class Comb(size: Int, private val feedback: Float, private val damp: Float) {
    private val buf = FloatArray(size)
    private var i = 0
    private var store = 0f
    fun process(x: Float): Float {
        val y = buf[i]
        store = y * (1f - damp) + store * damp
        buf[i] = x + store * feedback
        i = (i + 1) % buf.size
        return y
    }
}

private class Allpass(size: Int, private val feedback: Float) {
    private val buf = FloatArray(size)
    private var i = 0
    fun process(x: Float): Float {
        val y = buf[i]
        val out = -x + y
        buf[i] = x + y * feedback
        i = (i + 1) % buf.size
        return out
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "top.azarai.soundmap.audio.BinauralProcessorTest" --offline
```
Expected: PASS (3 tests). If `distance_addsReverbTail` is flaky on the near-tail bound, confirm `cWet` snaps to 0 at radius 0 — `near` tail must be ~0.

- [ ] **Step 5: Commit (optional)**

```bash
git add app/src/main/java/top/azarai/soundmap/audio/BinauralProcessor.kt app/src/test/java/top/azarai/soundmap/audio/BinauralProcessorTest.kt
git commit -m "feat: binaural DSP processor"
```

---

### Task 4: AudioTrack playback engine

**Files:**
- Create: `app/src/main/java/top/azarai/soundmap/audio/BinauralPlayer.kt`

**Interfaces:**
- Consumes: `DemoSource`/`DemoSourceId.create` (Task 1), `BinauralProcessor` (Task 3), `BinauralMapping` (Task 2), `DialPosition`.
- Produces:
  ```kotlin
  class BinauralPlayer(sampleRate: Int = 48000) {
      fun start(initial: DialPosition): Boolean   // returns false if AudioTrack failed to init
      fun stop()
      fun setPosition(p: DialPosition)
      fun setSource(id: DemoSourceId)
  }
  ```

This task has no unit test (Android `AudioTrack` needs a device); it is verified by compile + the device run in Task 6. Keep the file compiling and self-contained.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/top/azarai/soundmap/audio/BinauralPlayer.kt`:

```kotlin
package top.azarai.soundmap.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Renders the selected [DemoSource] through a [BinauralProcessor] and writes float PCM to a
 * stereo [AudioTrack]. Because we output our own samples, the spatial effect is baked into
 * the audio and reaches any device (incl. Bluetooth) without special permissions.
 */
class BinauralPlayer(private val sampleRate: Int = 48000) {

    private val processor = BinauralProcessor(sampleRate)
    @Volatile private var source: DemoSource = DemoSourceId.BEEP.create(sampleRate)
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun setPosition(p: DialPosition) {
        processor.setTarget(BinauralMapping.map(p, sampleRate))
    }

    fun setSource(id: DemoSourceId) {
        source = id.create(sampleRate)
    }

    /** @return true if playback started (AudioTrack initialized), false otherwise. */
    fun start(initial: DialPosition): Boolean {
        if (running) return true
        processor.setTarget(BinauralMapping.map(initial, sampleRate), snap = true)

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "getMinBufferSize returned $minBytes")
            return false
        }
        val bufferBytes = maxOf(minBytes, BLOCK_FRAMES * 2 * 4 * 4)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack not initialized (state=${t.state})")
            runCatching { t.release() }
            return false
        }

        track = t
        running = true
        t.play()
        thread = Thread({ loop(t) }, "SoundMapBinaural").also { it.start() }
        return true
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mono = FloatArray(BLOCK_FRAMES)
        val stereo = FloatArray(BLOCK_FRAMES * 2)
        while (running) {
            source.read(mono, BLOCK_FRAMES)
            processor.process(mono, BLOCK_FRAMES, stereo)
            val written = t.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write error $written")
                break
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    private companion object {
        const val TAG = "SoundMapPlayer"
        const val BLOCK_FRAMES = 480 // 10 ms @ 48 kHz
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:compileDebugKotlin --offline
```
Expected: BUILD SUCCESSFUL (deprecation warnings are fine; there should be none here). Note: the project still references the old engine, so a full `assembleDebug` is NOT expected to pass until Task 5 — only `compileDebugKotlin` of this new file matters, and it shares the module so this will compile the whole module; if old files still compile, it passes. If old effect files were already deleted, do Task 5 before building.

- [ ] **Step 3: Commit (optional)**

```bash
git add app/src/main/java/top/azarai/soundmap/audio/BinauralPlayer.kt
git commit -m "feat: AudioTrack binaural playback engine"
```

---

### Task 5: Integration cutover (engine, persistence, UI, service, manifest)

This is the cutover that wires the new pipeline in and deletes the legacy global-effect code.
All sub-steps must land together because deleting the old files removes types the old engine/UI
reference. Build + test are verified once at the end (Step 12).

**Files:**
- Rewrite: `audio/SoundMapEngine.kt`, `SoundMapViewModel.kt`, `data/SettingsStore.kt`, `ui/SoundMapScreen.kt`, `SoundMapService.kt`, `AndroidManifest.xml`, `res/values/strings.xml`
- Delete: `audio/SpatialMapping.kt`, `audio/EffectParams.kt`, `audio/SessionEffectManager.kt`, `audio/SessionEffectChain.kt`, `test/.../audio/SpatialMappingTest.kt`

**Interfaces:**
- Consumes: `BinauralPlayer` (Task 4), `DemoSourceId` (Task 1), `DialPosition`.
- Produces:
  ```kotlin
  data class EngineState(playing: Boolean, position: DialPosition, sourceId: DemoSourceId, error: String?)
  object SoundMapEngine { val state: StateFlow<EngineState>; fun play(): EngineState; fun stop(); fun setPosition(p); fun setSource(id) }
  data class SavedState(position: DialPosition, sourceId: DemoSourceId)   // SettingsStore
  ```

- [ ] **Step 1: Rewrite `SoundMapEngine.kt`**

Replace the entire contents of `app/src/main/java/top/azarai/soundmap/audio/SoundMapEngine.kt`:

```kotlin
package top.azarai.soundmap.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Observable snapshot of the demo player, surfaced to the UI. */
data class EngineState(
    val playing: Boolean = false,
    val position: DialPosition = DialPosition.CENTER,
    val sourceId: DemoSourceId = DemoSourceId.BEEP,
    val error: String? = null,
)

/**
 * Process-wide owner of the demo player. The [BinauralPlayer] renders our own audio, so the
 * spatial effect is baked into the samples and reaches any output (incl. Bluetooth). The
 * foreground service only keeps the process alive while playing.
 */
object SoundMapEngine {

    private var player: BinauralPlayer? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Starts playback of the current source at the current position. */
    fun play(): EngineState {
        val p = player ?: BinauralPlayer().also { player = it }
        p.setSource(_state.value.sourceId)
        val ok = p.start(_state.value.position)
        _state.update { it.copy(playing = ok, error = if (ok) null else "无法初始化音频输出") }
        return _state.value
    }

    /** Stops playback; audio output ends. */
    fun stop() {
        player?.stop()
        _state.update { it.copy(playing = false) }
    }

    /** Moves the sound around the listener (live while playing). */
    fun setPosition(position: DialPosition) {
        player?.setPosition(position)
        _state.update { it.copy(position = position) }
    }

    /** Swaps which demo sound plays (takes effect on the next block). */
    fun setSource(id: DemoSourceId) {
        player?.setSource(id)
        _state.update { it.copy(sourceId = id) }
    }
}
```

- [ ] **Step 2: Rewrite `SettingsStore.kt`** (persist position + source, drop `enabled`)

Replace the entire contents of `app/src/main/java/top/azarai/soundmap/data/SettingsStore.kt`:

```kotlin
package top.azarai.soundmap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.DialPosition

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundmap_settings")

/** Persisted UI state: last dial position and selected demo source. Never persists play state. */
data class SavedState(
    val position: DialPosition,
    val sourceId: DemoSourceId,
)

class SettingsStore(private val context: Context) {

    val state: Flow<SavedState> = context.dataStore.data.map { prefs ->
        SavedState(
            position = DialPosition(
                angleDeg = prefs[KEY_ANGLE] ?: 0f,
                radius = prefs[KEY_RADIUS] ?: 0f,
            ),
            sourceId = runCatching {
                DemoSourceId.valueOf(prefs[KEY_SOURCE] ?: DemoSourceId.BEEP.name)
            }.getOrDefault(DemoSourceId.BEEP),
        )
    }

    suspend fun save(position: DialPosition, sourceId: DemoSourceId) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANGLE] = position.angleDeg
            prefs[KEY_RADIUS] = position.radius
            prefs[KEY_SOURCE] = sourceId.name
        }
    }

    private companion object {
        val KEY_ANGLE = floatPreferencesKey("angle")
        val KEY_RADIUS = floatPreferencesKey("radius")
        val KEY_SOURCE = stringPreferencesKey("source")
    }
}
```

- [ ] **Step 3: Rewrite `SoundMapViewModel.kt`**

Replace the entire contents of `app/src/main/java/top/azarai/soundmap/SoundMapViewModel.kt`:

```kotlin
package top.azarai.soundmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.DialPosition
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.audio.SoundMapEngine
import top.azarai.soundmap.data.SettingsStore

/** Bridges the UI to [SoundMapEngine] / the foreground service and persists position + source. */
class SoundMapViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    val state: StateFlow<EngineState> = SoundMapEngine.state

    init {
        viewModelScope.launch {
            val saved = store.state.first()
            SoundMapEngine.setSource(saved.sourceId)
            SoundMapEngine.setPosition(saved.position)
        }
        // Persist position + source after they settle; never auto-plays.
        viewModelScope.launch {
            SoundMapEngine.state.debounce(400).collect { s ->
                store.save(s.position, s.sourceId)
            }
        }
    }

    fun toggle() {
        if (state.value.playing) {
            SoundMapEngine.stop()
            SoundMapService.stop(getApplication())
        } else {
            SoundMapEngine.play()
            if (state.value.playing) SoundMapService.start(getApplication())
        }
    }

    fun onPositionChange(position: DialPosition) {
        SoundMapEngine.setPosition(position)
    }

    fun onSourceChange(id: DemoSourceId) {
        SoundMapEngine.setSource(id)
    }
}
```

- [ ] **Step 4: Rewrite `SoundMapScreen.kt`** (new status text + source dropdown)

Replace the entire contents of `app/src/main/java/top/azarai/soundmap/ui/SoundMapScreen.kt`. Uses
only stable Material3 APIs (`DropdownMenu`/`OutlinedButton`) — no experimental `ExposedDropdownMenuBox`,
to stay robust across Compose BOM versions:

```kotlin
package top.azarai.soundmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.azarai.soundmap.R
import top.azarai.soundmap.SoundMapViewModel
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.ui.theme.OnSurface
import top.azarai.soundmap.ui.theme.OnSurfaceMuted
import kotlin.math.roundToInt

@Composable
fun SoundMapScreen(viewModel: SoundMapViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text("SoundMap", style = MaterialTheme.typography.headlineMedium, color = OnSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "佩戴耳机 · 点中心播放演示音 · 拖动圆盘移动声音的方位与距离",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            ClockDial(
                enabled = state.playing,
                position = state.position,
                onToggle = viewModel::toggle,
                onPositionChange = viewModel::onPositionChange,
            )

            Spacer(Modifier.height(32.dp))

            StatusReadout(state)

            Spacer(Modifier.height(24.dp))

            SourceDropdown(state.sourceId, viewModel::onSourceChange)
        }
    }
}

@Composable
private fun StatusReadout(state: EngineState) {
    val angle = state.position.angleDeg.roundToInt() % 360
    val distance = (state.position.radius * 100f).roundToInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "方向 $angle°   ·   距离 $distance%",
            style = MaterialTheme.typography.labelLarge,
            color = if (state.playing) OnSurface else OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        val text = when {
            state.error != null -> "⚠ ${state.error}"
            state.playing -> "演示音播放中"
            else -> "已停止 · 点中心开始播放"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DemoSourceId.labelRes(): Int = when (this) {
    DemoSourceId.BEEP -> R.string.source_beep
    DemoSourceId.NOISE -> R.string.source_noise
    DemoSourceId.CLICK -> R.string.source_click
}

@Composable
private fun SourceDropdown(selected: DemoSourceId, onSelect: (DemoSourceId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.source_label) + "：" + stringResource(selected.labelRes()))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DemoSourceId.entries.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(id.labelRes())) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}
```

> If `DemoSourceId.entries` fails to resolve on an older Kotlin, use `DemoSourceId.values()`.

- [ ] **Step 5: Rewrite `strings.xml`** (source labels + media-playback copy)

Replace the entire contents of `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SoundMap</string>
    <string name="service_channel_name">SoundMap 演示播放</string>
    <string name="service_notification_title">SoundMap 演示播放中</string>
    <string name="service_notification_text">空间音频演示正在播放。</string>
    <string name="service_stop_action">停止</string>
    <string name="source_label">演示音频</string>
    <string name="source_beep">滴声</string>
    <string name="source_noise">噪声脉冲</string>
    <string name="source_click">嗒嗒声</string>
</resources>
```

- [ ] **Step 6: Update `SoundMapService.kt`** to a `mediaPlayback` foreground service

In `app/src/main/java/top/azarai/soundmap/SoundMapService.kt`, replace the `startInForeground()` method body's `type` computation:

Old:
```kotlin
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
```
New:
```kotlin
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
```

Then, in the **same file**, update the two references to the renamed engine method (the engine no
longer has `disable()`; it is now `stop()`):

- In `onStartCommand`, the `ACTION_STOP` branch: change `SoundMapEngine.disable()` → `SoundMapEngine.stop()`.
- In `onDestroy`: change `SoundMapEngine.disable()` → `SoundMapEngine.stop()`.

(The notification strings now read as the demo-playback copy via Step 5.)

- [ ] **Step 7: Rewrite `AndroidManifest.xml`** (drop effect perms, switch FGS type)

Replace the entire contents of `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SoundMap">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.SoundMap">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".SoundMapService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
```

> `tools` namespace is retained for consistency though no longer strictly required.

- [ ] **Step 8: Delete the legacy global-effect code**

```bash
cd /home/azarai/Workspace/SoundMap
rm app/src/main/java/top/azarai/soundmap/audio/SpatialMapping.kt
rm app/src/main/java/top/azarai/soundmap/audio/EffectParams.kt
rm app/src/main/java/top/azarai/soundmap/audio/SessionEffectManager.kt
rm app/src/main/java/top/azarai/soundmap/audio/SessionEffectChain.kt
rm app/src/test/java/top/azarai/soundmap/audio/SpatialMappingTest.kt
```

- [ ] **Step 9: Build the APK + run the full unit suite**

```bash
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest --offline
```
Expected: BUILD SUCCESSFUL; tests = `DemoSourceTest` (4) + `BinauralMappingTest` (6) + `BinauralProcessorTest` (3) all pass. No reference to deleted types remains.

- [ ] **Step 10: Commit (optional)**

```bash
git add -A
git commit -m "feat: cut over to spatial audio demo player; remove global-effect code"
```

---

### Task 6: On-device verification

No unit test can cover the `AudioTrack` path; verify on the connected phone (model `2602BRT18C`,
Android 16) with Bluetooth headphones.

- [ ] **Step 1: Uninstall the old build, then install fresh**

The old build held `DUMP` / `MODIFY_AUDIO_SETTINGS` and a `specialUse` service; uninstall to clear
the old permission grant and service definition.

```bash
export PATH="/home/azarai/Android/Sdk/platform-tools:$PATH"
adb uninstall top.azarai.soundmap || true
export JAVA_HOME=/home/azarai/.sdkman/candidates/java/21.0.11-tem; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:installDebug --offline
```
Expected: `Installed on 1 device.`

- [ ] **Step 2: Launch and confirm no stale permissions**

```bash
export PATH="/home/azarai/Android/Sdk/platform-tools:$PATH"
adb shell monkey -p top.azarai.soundmap -c android.intent.category.LAUNCHER 1
adb shell dumpsys package top.azarai.soundmap | grep -iE "DUMP|MODIFY_AUDIO_ROUTING" || echo "no privileged perms (correct)"
```
Expected: `no privileged perms (correct)`.

- [ ] **Step 3: Manual listening check (human, with Bluetooth headset)**

1. Connect Bluetooth headphones.
2. In SoundMap, pick a source from the dropdown (start with **嗒嗒声 / Click train** — easiest to localize).
3. Tap the dial center → it shows **ON** and "演示音播放中"; you should hear the clicks.
4. Drag to **左(9点)** then **右(3点)**: the sound should jump clearly to that ear (ITD + level).
5. Drag **前(12点)** vs **后(6点)**: front brighter, back duller.
6. Drag outward to the rim: quieter with a sense of room/distance (reverb).
7. Tap center again → audio stops.

- [ ] **Step 4: Confirm the playback thread runs without underruns**

While playing, capture logs:
```bash
export PATH="/home/azarai/Android/Sdk/platform-tools:$PATH"
adb logcat -d -t 200 | grep -iE "SoundMapPlayer|AudioTrack.*underrun|AudioTrack.*error" || echo "no AudioTrack errors"
```
Expected: no `AudioTrack.write error` / `not initialized` lines from `SoundMapPlayer`; ideally `no AudioTrack errors`.

- [ ] **Step 5: Commit any device-driven fixes (optional)**

```bash
git add -A
git commit -m "fix: device-verification adjustments"
```

---

## Notes for the implementer

- **Order matters:** Tasks 1–4 each leave the module in a state where the *new* files compile but
  `assembleDebug` will still fail until Task 5 deletes the old engine references. Only run
  `:app:assembleDebug` at Task 5 Step 9; before that, use the per-test `--tests` commands and
  `:app:compileDebugKotlin` (Task 4) for fast feedback.
- **VPN/network:** every gradle command uses `--offline`. If one fails with a *missing dependency*
  (not a timeout), stop and report — do not remove `--offline`.
- **Latency:** capture→process is not involved here (we generate our own audio), so the only latency
  is the AudioTrack buffer (~tens of ms). If audio stutters, raise `bufferBytes` in `BinauralPlayer`.
- **If front/back is too subtle:** that's expected for a structural model; the spec's future-work
  note (HRIR convolution) is the upgrade path. Do not expand scope here.

