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
internal class BeepSource(private val sampleRate: Int) : DemoSource {
    private val period = (CYCLE_SEC * sampleRate).toLong()
    private val onLen = (BURST_SEC * sampleRate).toLong()
    private val fade = (FADE_SEC * sampleRate).toLong().coerceAtLeast(1)
    private val freqPhasePerSample = 2.0 * PI * 1000f / sampleRate
    private var t = 0L

    override fun read(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val ph = t % period
            out[i] = if (ph < onLen) {
                val env = minOf(ph, onLen - ph, fade).toFloat() / fade
                (sin(freqPhasePerSample * ph).toFloat()) * env
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
            out[i] = if (ph < tau * 6f) exp(-ph / tau) else 0f // click lasts 6 time-constants (decays to exp(-6) ≈ 0.0025)
            t++
        }
    }

    override fun reset() { t = 0L }
}
