package top.azarai.soundmap.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialMappingTest {

    @Test
    fun center_isNeutral() {
        val p = SpatialMapping.mapToParams(DialPosition.CENTER)
        assertEquals(0f, p.leftInputGainDb, 0.001f)
        assertEquals(0f, p.rightInputGainDb, 0.001f)
        assertEquals(0f, p.trebleShelfGainDb, 0.001f)
        assertEquals(ReverbPresets.NONE, p.reverbPreset)
        assertEquals(0.toShort(), p.virtualizerStrength)
    }

    @Test
    fun fullLeft_isLouderInLeftEar() {
        val p = SpatialMapping.mapToParams(DialPosition(angleDeg = 270f, radius = 1f))
        assertTrue("left ear should be louder than right", p.leftInputGainDb > p.rightInputGainDb)
    }

    @Test
    fun fullRight_isLouderInRightEar() {
        val p = SpatialMapping.mapToParams(DialPosition(angleDeg = 90f, radius = 1f))
        assertTrue("right ear should be louder than left", p.rightInputGainDb > p.leftInputGainDb)
    }

    @Test
    fun front_isBrighterThanBack() {
        val front = SpatialMapping.mapToParams(DialPosition(angleDeg = 0f, radius = 1f))
        val back = SpatialMapping.mapToParams(DialPosition(angleDeg = 180f, radius = 1f))
        assertTrue("front should have more treble than back", front.trebleShelfGainDb > back.trebleShelfGainDb)
        assertTrue(front.trebleShelfGainDb > 0f)
        assertTrue(back.trebleShelfGainDb < 0f)
    }

    @Test
    fun farther_meansMoreReverbAndMoreAttenuation() {
        val near = SpatialMapping.mapToParams(DialPosition(angleDeg = 0f, radius = 0.1f))
        val far = SpatialMapping.mapToParams(DialPosition(angleDeg = 0f, radius = 1f))
        assertTrue(far.reverbPreset > near.reverbPreset)
        // Both ears quieter when farther away (front has no pan, so compare master rolloff).
        assertTrue(far.leftInputGainDb < near.leftInputGainDb)
    }

    @Test
    fun edge_usesLargeHallReverb() {
        val p = SpatialMapping.mapToParams(DialPosition(angleDeg = 45f, radius = 1f))
        assertEquals(ReverbPresets.LARGEHALL, p.reverbPreset)
    }

    @Test
    fun radius_isClampedAndGainsStayNonPositive() {
        val p = SpatialMapping.mapToParams(DialPosition(angleDeg = 30f, radius = 5f))
        assertTrue(p.leftInputGainDb <= 0f)
        assertTrue(p.rightInputGainDb <= 0f)
        assertTrue(p.virtualizerStrength <= 1000)
    }
}
