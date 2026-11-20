package com.aiyifan.app.feature.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesSkipMarkersTest {
    @Test
    fun `marks use elapsed time for intro and remaining time for outro`() {
        assertEquals(SeriesSkipMarkers(2_620L, 90L), SeriesSkipMarkers.fromPlayback(2_620_000L, 2_710_000L))
        assertEquals(SeriesSkipMarkers(81L, 19L), SeriesSkipMarkers.fromPlayback(80_600L, 100_000L))
    }

    @Test
    fun `unavailable and invalid positions cannot be marked`() {
        val unavailable = SeriesSkipMarkers()
        assertEquals(unavailable, SeriesSkipMarkers.fromPlayback(2_000L, 0L))
        assertEquals(unavailable, SeriesSkipMarkers.fromPlayback(0L, 10_000L))
        assertEquals(unavailable, SeriesSkipMarkers.fromPlayback(10_000L, 10_000L))
        assertEquals(unavailable, SeriesSkipMarkers.fromPlayback(-1L, 10_000L))
        assertEquals(unavailable, SeriesSkipMarkers.fromPlayback(10_001L, 10_000L))
    }

    @Test
    fun `rounding cannot turn a mark into zero or the full episode`() {
        assertEquals(SeriesSkipMarkers(), SeriesSkipMarkers.fromPlayback(200L, 10_000L))
        assertEquals(SeriesSkipMarkers(), SeriesSkipMarkers.fromPlayback(9_800L, 10_000L))
        assertEquals(SeriesSkipMarkers(null, 10L), SeriesSkipMarkers.fromPlayback(60_000_000L, 60_010_000L))
    }
}
