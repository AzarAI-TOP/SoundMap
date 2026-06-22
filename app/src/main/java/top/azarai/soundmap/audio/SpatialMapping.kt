package top.azarai.soundmap.audio

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Translates a [DialPosition] into [EffectParams] for the built-in Android effects.
 *
 * This is the heart of SoundMap's "2D" illusion, built only from effects a non-root
 * app can attach to the global output mix:
 *  - lateral angle  -> per-channel gain (pan the far ear down),
 *  - front/back     -> a subtle treble shelf (pinna-cue approximation; honestly weak),
 *  - distance (radius) -> reverb depth + a small overall volume rolloff.
 *
 * Pure and deterministic so it can be unit tested without a device.
 */
object SpatialMapping {

    /** Max attenuation of the far ear at full lateral offset. */
    const val MAX_PAN_DB = 12f

    /** Max treble shelf swing between full-front and full-back. */
    const val MAX_TREBLE_DB = 6f

    /** Max overall volume rolloff at the far edge of the dial. */
    const val MAX_DISTANCE_ATTEN_DB = 6f

    fun mapToParams(position: DialPosition): EffectParams {
        val radius = position.radius.coerceIn(0f, 1f)
        val theta = Math.toRadians(position.angleDeg.toDouble())

        // Decompose into right(+)/left(-) and front(+)/back(-) components, scaled by distance.
        val horizontal = (radius * sin(theta)).toFloat()   // -1 (left) .. +1 (right)
        val vertical = (radius * cos(theta)).toFloat()      // -1 (back) .. +1 (front)

        val masterDb = -MAX_DISTANCE_ATTEN_DB * radius
        // Attenuate the ear opposite the chosen direction.
        val panLeftDb = if (horizontal > 0f) -MAX_PAN_DB * horizontal else 0f
        val panRightDb = if (horizontal < 0f) -MAX_PAN_DB * -horizontal else 0f

        return EffectParams(
            leftInputGainDb = masterDb + panLeftDb,
            rightInputGainDb = masterDb + panRightDb,
            trebleShelfGainDb = vertical * MAX_TREBLE_DB,
            reverbPreset = reverbForRadius(radius),
            virtualizerStrength = (radius * 1000f).roundToInt().coerceIn(0, 1000).toShort(),
        )
    }

    private fun reverbForRadius(radius: Float): Short = when {
        radius < 0.15f -> ReverbPresets.NONE
        radius < 0.40f -> ReverbPresets.SMALLROOM
        radius < 0.65f -> ReverbPresets.MEDIUMROOM
        radius < 0.85f -> ReverbPresets.LARGEROOM
        else -> ReverbPresets.LARGEHALL
    }
}
