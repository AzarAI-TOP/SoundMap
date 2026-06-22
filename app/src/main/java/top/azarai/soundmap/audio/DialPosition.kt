package top.azarai.soundmap.audio

/**
 * A point picked on the clock dial.
 *
 * @param angleDeg direction in degrees, measured clockwise from straight up.
 *   0 = front (12 o'clock), 90 = right (3 o'clock), 180 = back (6 o'clock),
 *   270 = left (9 o'clock).
 * @param radius distance from the center, normalized to 0f (center) .. 1f (edge).
 */
data class DialPosition(
    val angleDeg: Float,
    val radius: Float,
) {
    companion object {
        /** Center of the dial: no offset, neutral effect. */
        val CENTER = DialPosition(angleDeg = 0f, radius = 0f)
    }
}
