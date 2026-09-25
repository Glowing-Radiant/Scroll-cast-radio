package com.scrollcast.radio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCurvesTest {
    @Test
    fun bandCentersRiseAndSitInsideTheirBands() {
        var previousEdge = 20f
        EqCurves.BAND_EDGES.forEachIndexed { band, edge ->
            val center = EqCurves.bandCenter(band)
            assertTrue(center > previousEdge && center < edge)
            previousEdge = edge
        }
    }

    @Test
    fun interpolatesOnLogFrequency() {
        val preset = listOf(1_000f to 6f, 60f to 0f, 14_000f to -3f)
        assertEquals(0f, EqCurves.interpolate(preset, 30f), 0.001f)      // below range: first point
        assertEquals(-3f, EqCurves.interpolate(preset, 18_000f), 0.001f) // above range: last point
        assertEquals(6f, EqCurves.interpolate(preset, 1_000f), 0.001f)
        // Halfway between 60 Hz and 1 kHz on a log scale is ~245 Hz.
        assertEquals(3f, EqCurves.interpolate(preset, 244.95f), 0.01f)
    }

    @Test
    fun bassBoostOnlyTouchesLows() {
        assertEquals(8f, EqCurves.bassBoost(40f), 0f)
        assertEquals(0f, EqCurves.bassBoost(2_000f), 0f)
    }
}
