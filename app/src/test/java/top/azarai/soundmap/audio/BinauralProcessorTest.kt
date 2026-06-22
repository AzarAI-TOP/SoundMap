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
        val len = 8192
        fun render(radius: Float): FloatArray {
            val proc = BinauralProcessor(sr)
            proc.setTarget(BinauralMapping.map(DialPosition(0f, radius), sr), snap = true)
            val mono = FloatArray(len).also { it[0] = 1f }
            val out = FloatArray(2 * len)
            proc.process(mono, len, out)
            return out
        }
        fun tail(out: FloatArray): Float {
            var s = 0f
            for (k in 2000 until len) { s += abs(out[2 * k]) + abs(out[2 * k + 1]) }
            return s
        }
        val far = render(1f)
        val near = render(0f)
        assertTrue("far has audible reverb tail", tail(far) > 1e-3f)
        assertTrue("far tail exceeds near tail", tail(far) > tail(near))
    }
}
