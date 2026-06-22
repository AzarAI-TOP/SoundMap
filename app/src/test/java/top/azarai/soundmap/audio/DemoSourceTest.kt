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
