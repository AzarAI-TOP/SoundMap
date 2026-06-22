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
private class Reverb(private val sampleRate: Int) {
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
