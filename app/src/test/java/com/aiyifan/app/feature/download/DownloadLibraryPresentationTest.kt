package com.aiyifan.app.feature.download

import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import org.junit.Assert.*
import org.junit.Test

class DownloadLibraryPresentationTest {
    @Test fun `watch percentage clamps valid progress and omits unknown duration`() {
        assertEquals(0, watchPercentage(0, 100))
        assertEquals(65, watchPercentage(650, 1000))
        assertEquals(100, watchPercentage(1200, 1000))
        assertNull(watchPercentage(500, 0))
        assertNull(watchPercentage(-1, 1000))
    }

    @Test fun `byte progress uses measured length not segment percentage`() {
        val progress = downloadProgress(250, 1000, 80f, true)
        assertEquals(25, progress.percent)
        assertFalse(progress.segmentBased)
        assertFalse(progress.indeterminate)
    }

    @Test fun `unknown length retains segment progress without inventing total bytes`() {
        val progress = downloadProgress(250, -1, 38.9f, true)
        assertEquals(38, progress.percent)
        assertTrue(progress.segmentBased)
        assertNull(progress.totalBytes)
    }

    @Test fun `unknown paused or waiting progress stays still`() {
        assertFalse(downloadProgress(0, -1, -1f, false).indeterminate)
        assertTrue(downloadProgress(0, -1, -1f, true).indeterminate)
        assertNull(downloadProgress(0, -1, Float.NaN, true).percent)
    }

    @Test fun `film snapshot retains undownloaded episodes and original ordering`() {
        val detail = detail(listOf(episode("a"), episode("b"), episode("c")))
        val entries = listOf(entry("c", 2, 300), entry("b", 1, 200, LibraryEpisodeState.WAITING_WIFI))
        val film = downloadFilms(entries) { detail }.single()
        assertEquals(listOf("a", "b", "c"), film.episodes.map { it.episode.episodeKey })
        assertEquals(LibraryEpisodeState.NOT_DOWNLOADED, film.episodes[0].state)
        assertEquals(LibraryEpisodeState.WAITING_WIFI, film.episodes[1].state)
        assertEquals(300L, film.totalBytes)
        assertEquals(1, film.completed.size)
    }

    @Test fun `legacy groups expose only known episodes and prefer downloaded history`() {
        val film = downloadFilms(listOf(entry("c", 2, 300), entry("a", 0, 100))) { null }.single()
        assertEquals(listOf("a", "c"), film.episodes.map { it.episode.episodeKey })
        assertEquals("task-c", film.playId("c"))
        assertEquals("task-a", film.playId("not-downloaded"))
    }

    @Test fun `films without completed episodes stay out of completed library`() {
        assertTrue(downloadFilms(listOf(entry("a", 0, 30, LibraryEpisodeState.DOWNLOADING))) { null }.isEmpty())
        assertTrue(downloadFilms(emptyList()) { null }.isEmpty())
    }

    private fun episode(key: String) = Episode(key, "第 $key 集", 1, null)
    private fun detail(episodes: List<Episode>) = VideoDetail("film", "影片", "", 2, episodes = episodes)
    private fun entry(key: String, order: Int, bytes: Long, state: LibraryEpisodeState = LibraryEpisodeState.DOWNLOADED) =
        LibraryDownload("task-$key", detail(listOf(episode(key))), episode(key), order, bytes, state)
}
