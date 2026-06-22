package top.azarai.soundmap.audio

/**
 * Concrete parameters for the built-in Android effects, derived from a [DialPosition].
 * Pure data — no Android dependencies — so it can be unit tested on the JVM.
 *
 * @param leftInputGainDb gain applied to the left channel (dB, <= 0).
 * @param rightInputGainDb gain applied to the right channel (dB, <= 0).
 *   Together these encode lateral panning + a small overall distance rolloff.
 * @param trebleShelfGainDb high-frequency shelf gain (dB). Positive = brighter
 *   (a "front" timbre cue), negative = duller (a "behind you" cue).
 * @param reverbPreset one of [ReverbPresets]; more reverb = farther / larger space.
 * @param virtualizerStrength headphone-widening strength, 0..1000.
 */
data class EffectParams(
    val leftInputGainDb: Float,
    val rightInputGainDb: Float,
    val trebleShelfGainDb: Float,
    val reverbPreset: Short,
    val virtualizerStrength: Short,
) {
    companion object {
        /** Fully neutral output (used at the dial center / when disabled). */
        val NEUTRAL = EffectParams(
            leftInputGainDb = 0f,
            rightInputGainDb = 0f,
            trebleShelfGainDb = 0f,
            reverbPreset = ReverbPresets.NONE,
            virtualizerStrength = 0,
        )
    }
}

/**
 * Mirror of android.media.audiofx.PresetReverb preset constants, kept here so the
 * mapping logic stays free of Android imports and remains unit-testable.
 */
object ReverbPresets {
    const val NONE: Short = 0
    const val SMALLROOM: Short = 1
    const val MEDIUMROOM: Short = 2
    const val LARGEROOM: Short = 3
    const val MEDIUMHALL: Short = 4
    const val LARGEHALL: Short = 5
    const val PLATE: Short = 6
}
