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
