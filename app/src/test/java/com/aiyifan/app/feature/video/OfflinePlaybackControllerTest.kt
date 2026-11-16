package com.aiyifan.app.feature.video

import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.data.FakeCatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackControllerTest {
    private val first = Episode("first", "第一集", 1, mediaUrl = "https://invalid/first.m3u8")
    private val second = Episode("second", "第二集", 2, mediaUrl = "https://invalid/second.m3u8")
    private val third = Episode("third", "第三集", 3, mediaUrl = "https://invalid/third.m3u8")
    private val detail = VideoDetail("video", "离线视频", "", 1, episodes = listOf(first, second, third))
    private val entries get() = listOf(OfflinePlaybackEntry("three", detail, third, 2), OfflinePlaybackEntry("one", detail, first, 0))

    @Test fun `offline navigation uses completed episodes without resolving network and preserves history`() = runBlocking {
        val history = FakeCatalogRepository()
        val repository = object : CatalogRepository by history {
            override suspend fun resolvePlayback(detail: VideoDetail, episode: Episode, forceRefresh: Boolean): Episode = error("network must not be used")
        }
        val engine = Engine()
        val controller = controller(engine, repository)
        controller.openVideo("video", "one")
        assertTrue(controller.loadOfflineDownload("one") is EpisodeSwitchResult.Switched)
        assertEquals(listOf(first, third), controller.activeSession!!.detail.episodes)
        assertTrue(controller.switchEpisode(1) is EpisodeSwitchResult.Switched)
        assertEquals("three", controller.activeSession!!.offlineDownloadId)
        assertEquals(listOf("one", "three"), engine.offlineRequests)
        assertEquals(EpisodeSwitchResult.Unavailable, controller.switchEpisode(1))
        controller.saveHistory()
        assertEquals("third", history.getHistory().single().episodeKey)
    }

    @Test fun `same media switches between online and offline and between offline task ids`() {
        val engine = Engine()
        val controller = controller(engine)
        controller.openVideo("video")
        controller.prepare(detail, first)
        val onlineSelection = controller.selectionToken
        controller.openVideo("video", "one")
        assertTrue(controller.selectionToken != onlineSelection)
        assertNull(controller.activeSession)
        controller.loadOfflineDownload("one")
        assertTrue(controller.isOffline)
        controller.openVideo("video", "three")
        assertNull(controller.activeSession)
        controller.loadOfflineDownload("three")
        assertEquals("three", controller.activeSession!!.offlineDownloadId)
        controller.openVideo("video")
        assertFalse(controller.isOffline)
        assertNull(controller.activeSession)
        controller.prepare(detail, first)
        assertEquals(2, engine.onlineRequests)
    }

    @Test fun `missing task and cache failure retain offline identity and never fallback`() {
        val engine = Engine()
        val controller = controller(engine)
        controller.openVideo("video", "missing")
        assertEquals(EpisodeSwitchResult.Failed, controller.loadOfflineDownload("missing"))
        assertTrue(controller.isOffline)
        assertEquals("missing", controller.requestedOfflineDownloadId)
        assertEquals(0, engine.onlineRequests)
        controller.openVideo("video", "one")
        engine.rejectOffline = true
        assertEquals(EpisodeSwitchResult.Failed, controller.loadOfflineDownload("one"))
        assertTrue(controller.hasOfflinePlaybackError)
        assertNull(controller.activeSession)
    }

    @Test fun `offline auto advance skips unavailable episodes and reports asynchronous cache errors`() = runBlocking {
        val engine = Engine()
        val controller = controller(engine)
        controller.openVideo("video", "one")
        controller.loadOfflineDownload("one")
        engine.hasEnded = true
        assertTrue(controller.advanceToNextEpisodeIfEnded() is EpisodeSwitchResult.Switched)
        assertEquals("three", controller.activeSession!!.offlineDownloadId)
        engine.hasPlaybackError = true
        assertTrue(controller.hasOfflinePlaybackError)
        assertEquals(0, engine.onlineRequests)
        assertEquals("three", controller.requestedOfflineDownloadId)
    }

    @Test fun `removal releases only matching active download without instantiating provider`() {
        var creates = 0
        val engine = Engine()
        val provider = VideoPlaybackControllerProvider { creates++; controller(engine) }
        provider.stopOfflineDownload("one")
        assertEquals(0, creates)
        val controller = provider.get()
        controller.openVideo("video", "one")
        controller.loadOfflineDownload("one")
        provider.stopOfflineDownload("three")
        assertFalse(controller.isReleased)
        provider.stopOfflineDownload("one")
        assertTrue(controller.isReleased)
        assertEquals(1, engine.releases)
    }

    private fun controller(engine: Engine, repository: CatalogRepository = FakeCatalogRepository()) = VideoPlaybackController(
        engine, repository, object : PlaybackSession { override fun release() = Unit },
        offlineCatalog = object : OfflinePlaybackCatalog {
            override fun get(id: String) = entries.firstOrNull { it.id == id }
            override fun completed(mediaKey: String) = entries.filter { it.detail.mediaKey == mediaKey }
        },
    )

    private class Engine : PlaybackEngine {
        override var isPlaying = false
        override var hasEnded = false
        override var hasPlaybackError = false
        override val currentPosition = 120L
        override val duration = 1000L
        var onlineRequests = 0
        var releases = 0
        var rejectOffline = false
        val offlineRequests = mutableListOf<String>()
        override fun setMediaUrl(mediaUrl: String) { onlineRequests++ }
        override fun setOfflineMedia(downloadId: String) {
            if (rejectOffline) error("cache missing")
            hasEnded = false
            offlineRequests += downloadId
        }
        override fun prepare() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun addPositionListener(listener: (Long) -> Unit): () -> Unit = {}
        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun attach(playerView: androidx.media3.ui.PlayerView) = Unit
        override fun detach() = Unit
        override fun release() { releases++ }
    }
}
