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
