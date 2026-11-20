package com.aiyifan.app.feature.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSkipPolicyTest {

    @Test
    fun `out of range and cleared settings independently fall back to server times`() {
        assertEquals(
            AutoSkipPolicy.EpisodeTimes(introMs = 15_000L, outroStartMs = 80_000L),
            AutoSkipPolicy.episodeTimes(100_000L, 10L, 80L, SeriesSkipTimes(15L, 100L)),
        )
        assertEquals(
            AutoSkipPolicy.EpisodeTimes(introMs = 10_000L, outroStartMs = 75_000L),
            AutoSkipPolicy.episodeTimes(100_000L, 10L, 80L, SeriesSkipTimes(outroSeconds = 25L)),
        )
    }

    @Test
    fun `initial position preserves an existing resume position`() {
        assertEquals(12_345L, AutoSkipPolicy.initialPositionMs(12_345L, 90L, enabled = true))
    }

    @Test
    fun `initial position skips a valid intro for a new playback`() {
        assertEquals(90_000L, AutoSkipPolicy.initialPositionMs(0L, 90L, enabled = true))
    }

    @Test
    fun `initial position ignores an intro value that cannot convert to milliseconds`() {
        assertEquals(0L, AutoSkipPolicy.initialPositionMs(0L, Long.MAX_VALUE, enabled = true))
    }

    @Test
    fun `initial position does not skip when disabled or zero`() {
        assertEquals(0L, AutoSkipPolicy.initialPositionMs(0L, 90L, enabled = false))
        assertEquals(0L, AutoSkipPolicy.initialPositionMs(0L, 0L, enabled = true))
    }

    @Test
    fun `outro advances once when another episode is available`() {
        assertTrue(
            AutoSkipPolicy.shouldAdvance(
                positionMs = 80_000L,
                outroSecond = 80L,
                enabled = true,
                hasNextEpisode = true,
                hasAdvanced = false,
            ),
        )
        assertFalse(
            AutoSkipPolicy.shouldAdvance(
                positionMs = 80_000L,
                outroSecond = 80L,
                enabled = true,
                hasNextEpisode = true,
                hasAdvanced = true,
            ),
        )
    }

    @Test
    fun `outro does not advance without a next episode`() {
        assertFalse(
            AutoSkipPolicy.shouldAdvance(
                positionMs = 80_000L,
                outroSecond = 80L,
                enabled = true,
                hasNextEpisode = false,
                hasAdvanced = false,
            ),
        )
    }

    @Test
    fun `outro ignores a value that cannot convert to milliseconds`() {
        assertFalse(
            AutoSkipPolicy.shouldAdvance(
                positionMs = 0L,
                outroSecond = Long.MAX_VALUE,
                enabled = true,
                hasNextEpisode = true,
                hasAdvanced = false,
            ),
        )
    }
}
