package com.aiyifan.app.feature.download

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiyifan.app.R
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.data.FakeCatalogRepository
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.download.downloadId
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.feature.main.MainActivity
import com.google.android.material.tabs.TabLayout
import com.aiyifan.app.feature.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineDownloadDeviceTest {
    @org.junit.Before fun enableOnlineSession() { com.aiyifan.app.core.startup.StartupSession.mode = com.aiyifan.app.core.startup.AppMode.ONLINE }
    @org.junit.After fun clearOnlineSession() { com.aiyifan.app.core.startup.StartupSession.mode = null }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test(timeout = 30_000)
    fun queueShowsActualCacheSizeAfterDownloadAndRemovalWithoutReopening() = runBlocking {
        FixtureServer(instrumentation.context).use { server ->
            val repository = withContext(Dispatchers.Main) {
                OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                    override suspend fun resolveDownload(detail: VideoDetail, episode: Episode) = episode
                }).also { it.awaitLoaded() }
            }
            val previousNetwork = repository.mobileDataAllowed
            val previousRequirements = withContext(Dispatchers.Main) { repository.manager.requirements }
            val episode = Episode("storage-episode", "1", 1, server.url("/playback-test.mp4"))
            val detail = VideoDetail("storage-fixture", "缓存容量验证", "", 1, episodes = listOf(episode))
            val id = downloadId(detail.mediaKey, episode.episodeKey)
            try {
                withContext(Dispatchers.Main) {
                    repository.remove(id)
                    // This fixture is served on loopback and need not wait for internet validation.
                    repository.manager.requirements = Requirements(0)
                }
                val before = repository.storageBytes().first
                ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                    suspend fun awaitUsedStorage(bytes: Long) = await {
                        val expected = context.getString(R.string.download_storage, Formatter.formatShortFileSize(context, bytes), "")
                        var matches = false
                        scenario.onActivity {
                            matches = it.findViewById<TextView>(R.id.storage).text.startsWith(expected)
                        }
                        matches
                    }
                    awaitUsedStorage(before)
                    withContext(Dispatchers.Main) {
                        repository.setMobileDataAllowed(true)
                        repository.enqueue(detail, episode)
                    }
                    await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
                    val after = repository.storageBytes().first
                    assertTrue(after > before)
                    awaitUsedStorage(after)
                    withContext(Dispatchers.Main) { repository.remove(id) }
                    assertEquals(before, repository.storageBytes().first)
                    awaitUsedStorage(before)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    repository.remove(id)
                    repository.manager.requirements = previousRequirements
                    repository.setMobileDataAllowed(previousNetwork)
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun offlineOverlayContinuesAfterServerStopsAndReturnsWithSameSession() = runBlocking {
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        }
        val previousMode = shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
        val restoreMode = Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
            .find(previousMode)?.groupValues?.get(1) ?: "default"
        val server = FixtureServer(instrumentation.context)
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode) = episode
            })
        }
        val previousNetwork = repository.mobileDataAllowed
        val episode = Episode("overlay-offline-episode", "1", 1, server.url("/playback-test.mp4"))
        val detail = VideoDetail("overlay-offline-fixture", "离线悬浮验证", "", 1, episodes = listOf(episode))
        var id: String? = null
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            val download = withContext(Dispatchers.Main) {
                repository.setMobileDataAllowed(true)
                repository.enqueue(detail, episode)
            }
            id = download
            await { repository.get(download)?.download?.state == Download.STATE_COMPLETED }
            withContext(Dispatchers.Main) { AppGraph.downloadRepository.awaitLoaded() }
            server.close()
            val requestCount = server.requests.size
            ActivityScenario.launch<VideoPlayerActivity>(VideoPlayerActivity.offlineIntent(context, download, detail.mediaKey)).use { scenario ->
                val controller = withContext(Dispatchers.Main) { AppGraph.videoPlaybackController }
                await { withContext(Dispatchers.Main) { controller.isPlaying } }
                scenario.onActivity {
                    controller.seekTo(2_000L)
                    it.findViewById<android.widget.ImageButton>(com.aiyifan.app.R.id.floatingWindowButton).performClick()
                }
                await { withContext(Dispatchers.Main) { controller.hasOverlayOwner && controller.isPlaying && controller.currentPositionMs > 2_500L } }
                withContext(Dispatchers.Main) {
                    assertEquals(download, controller.activeSession?.offlineDownloadId)
                    controller.pause()
                    controller.seekTo(5_000L)
                    context.startActivity(VideoPlayerActivity.offlineIntent(context, download, detail.mediaKey)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                await { withContext(Dispatchers.Main) { !controller.hasOverlayOwner } }
                scenario.onActivity {
                    assertEquals(download, controller.activeSession?.offlineDownloadId)
                    assertEquals(episode.episodeKey, controller.activeSession?.episode?.episodeKey)
                    assertEquals(5_000L, controller.currentPositionMs)
                    assertFalse(controller.playWhenReady)
                    assertFalse(controller.hasOfflinePlaybackError)
                    assertNotNull(it.findViewById<androidx.media3.ui.PlayerView>(com.aiyifan.app.R.id.playerView).player)
                }
            }
            assertEquals(requestCount, server.requests.size)
        } finally {
            context.stopService(com.aiyifan.app.feature.video.FloatingPlayerService.intent(context))
            withContext(Dispatchers.Main) {
                AppGraph.videoPlaybackController.release()
                id?.let { repository.remove(it) }
                repository.setMobileDataAllowed(previousNetwork)
                AppGraph.catalogRepository.removeHistory(setOf(detail.mediaKey))
            }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $restoreMode")
            server.close()
        }
    }

    /** Run seed, force-stop the app, then run play with -e offlineColdPhase seed/play. */
    @Test(timeout = 120_000)
    fun persistedDownloadPlaysInFreshProcess() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("offlineColdPhase")
        org.junit.Assume.assumeTrue(phase == "seed" || phase == "play")
        val mediaKey = "offline-cold-fixture"
        val id = downloadId(mediaKey, "cold")
        if (phase == "seed") {
            FixtureServer(instrumentation.context).use { server ->
                val repository = withContext(Dispatchers.Main) {
                    OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                        override suspend fun resolveDownload(detail: VideoDetail, episode: Episode) = episode
                    })
                }
                val previousNetwork = repository.mobileDataAllowed
                try {
                    val episode = Episode("cold", "冷启动验证", 0, server.url("/download/aes/index.m3u8"))
                    val detail = VideoDetail(mediaKey, "跨进程离线验证", "", 1, episodes = listOf(episode))
                    withContext(Dispatchers.Main) {
                        repository.setMobileDataAllowed(true)
                        repository.remove(id)
                        repository.enqueue(detail, episode)
                    }
                    await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
                    withContext(Dispatchers.Main) { AppGraph.catalogRepository.saveHistory(detail, episode, 6_000, 20_000) }
                } finally {
                    withContext(Dispatchers.Main) { repository.setMobileDataAllowed(previousNetwork) }
                }
            }
        } else {
            val repository = withContext(Dispatchers.Main) { AppGraph.downloadRepository.also { it.awaitLoaded() } }
            try {
                assertEquals(Download.STATE_COMPLETED, repository.get(id)?.download?.state)
                ActivityScenario.launch<VideoPlayerActivity>(VideoPlayerActivity.offlineIntent(context, id, mediaKey)).use { scenario ->
                    await { withContext(Dispatchers.Main) { AppGraph.videoPlaybackController.isPlaying && AppGraph.videoPlaybackController.currentPositionMs >= 6_000 } }
                    scenario.onActivity { AppGraph.videoPlaybackController.seekTo(10_000) }
                    await { withContext(Dispatchers.Main) { AppGraph.videoPlaybackController.isPlaying && AppGraph.videoPlaybackController.currentPositionMs >= 10_200 } }
                    withContext(Dispatchers.Main) {
                        assertEquals(id, AppGraph.videoPlaybackController.activeSession?.offlineDownloadId)
                        assertFalse(AppGraph.videoPlaybackController.hasOfflinePlaybackError)
                        AppGraph.videoPlaybackController.pause()
                    }
                    screenshot("downloads-cold-offline.png")
                }
            } finally {
                withContext(Dispatchers.Main) { repository.remove(id); AppGraph.catalogRepository.removeHistory(setOf(mediaKey)) }
            }
        }
    }

    @Test(timeout = 180_000)
    fun hlsIndependentAudioAndAesAndMp4PlayAfterSourceServerStops() = runBlocking {
        val server = FixtureServer(instrumentation.context)
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode) = episode
            })
        }
        val previousNetwork = repository.mobileDataAllowed
        val ids = mutableListOf<String>()
        try {
            withContext(Dispatchers.Main) { repository.setMobileDataAllowed(true) }
            for ((index, path) in listOf("/playback-test.mp4", "/download/master.m3u8", "/download/aes/index.m3u8", "/download/aes/index.m3u8").withIndex()) {
                android.util.Log.i("OfflineFixture", "Preparing $path")
                val episode = Episode("episode-$index", "第 ${index + 1} 集", index, server.url(path))
                val detail = VideoDetail("offline-device-fixture", "离线下载验证", "", 1, episodes = listOf(episode))
                val id = withContext(Dispatchers.Main) { repository.enqueue(detail, episode) }
                android.util.Log.i("OfflineFixture", "Enqueued $path")
                ids += id
                await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
                val completed = requireNotNull(repository.get(id))
                when (path) {
                    "/playback-test.mp4" -> assertEquals(completed.bytesDownloaded, completed.download.contentLength)
                    "/download/master.m3u8" -> assertTrue((completed.metadata?.estimatedTotalBytes ?: 0) > 0)
                    "/download/aes/index.m3u8" -> assertNull(completed.metadata?.estimatedTotalBytes)
                }
                android.util.Log.i("OfflineFixture", "Completed $path")
                val countBeforeDuplicate = server.requests.size
                assertEquals(id, withContext(Dispatchers.Main) { repository.enqueue(detail, episode) })
                assertEquals(countBeforeDuplicate, server.requests.size)
            }
            assertTrue(server.requests.any { it.startsWith("/download/audio/") && it.endsWith(".ts") })
            assertTrue(server.requests.any { it.startsWith("/download/high/") && it.endsWith(".ts") })
            assertFalse("Must not download unselected low-resolution segments", server.requests.any { it.startsWith("/download/low/") && it.endsWith(".ts") })
            assertTrue(server.requests.any { it.endsWith("key.bin") })
            // Two catalog episodes deliberately share every segment and key URL.
            val removed = ids.removeAt(2)
            withContext(Dispatchers.Main) { repository.remove(removed) }
            withContext(Dispatchers.Main) { AppGraph.downloadRepository.awaitLoaded() }
            server.close()
            val requestsBeforeOffline = server.requests.size
            for (id in ids) {
                android.util.Log.i("OfflineFixture", "Playing $id")
                ActivityScenario.launch<VideoPlayerActivity>(VideoPlayerActivity.offlineIntent(context, id, "offline-device-fixture")).use { scenario ->
                    await { withContext(Dispatchers.Main) { AppGraph.videoPlaybackController.durationMs > 0 && !AppGraph.videoPlaybackController.hasOfflinePlaybackError } }
                    scenario.onActivity { AppGraph.videoPlaybackController.seekTo(10_000) }
                    await { withContext(Dispatchers.Main) { AppGraph.videoPlaybackController.isPlaying && AppGraph.videoPlaybackController.currentPositionMs >= 10_200 } }
                    withContext(Dispatchers.Main) {
                        assertTrue(AppGraph.videoPlaybackController.isOffline)
                        assertEquals(id, AppGraph.videoPlaybackController.activeSession?.offlineDownloadId)
                        assertFalse(AppGraph.videoPlaybackController.hasOfflinePlaybackError)
                        AppGraph.videoPlaybackController.pause()
                    }
                }
            }
            assertEquals("Offline playback must not make HTTP requests", requestsBeforeOffline, server.requests.size)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_DOWNLOADS)!!.select() }
                await {
                    var rendered = false
                    scenario.onActivity {
                        val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.films)
                        val expectedFilms = AppGraph.downloadRepository.tasks.value
                            .filter { it.download.state == Download.STATE_COMPLETED }
                            .mapNotNull { it.metadata?.detail?.mediaKey }.distinct().size
                        rendered = list.adapter?.itemCount == expectedFilms && list.childCount > 0
                    }
                    rendered
                }
                instrumentation.waitForIdleSync()
                screenshot("downloads-completed.png")
            }
        } finally {
            android.util.Log.i("OfflineFixture", "Cleaning up")
            server.close()
            withContext(Dispatchers.Main) {
                for (id in ids) repository.remove(id)
                AppGraph.catalogRepository.removeHistory(setOf("offline-device-fixture"))
                repository.setMobileDataAllowed(previousNetwork)
            }
        }
    }

    @Test
    fun queueControlsEditSelectAndStartAcrossRecreation() = runBlocking<Unit> {
        // Hold source preparation so queued tasks cannot finish before the UI is exercised.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode): Episode {
                    gate.await()
                    return episode
                }
            })
        }
        val episodes = (1..4).map { Episode("batch-$it", "第 $it 集", it, null) }
        val detail = VideoDetail("batch-fixture", "星夜来信：写给每一个在漫长旅途中等待重逢的人", "", 1, episodes = episodes)
        val ids = episodes.map { downloadId(detail.mediaKey, it.episodeKey) }
        withContext(Dispatchers.Main) { repository.awaitLoaded() }
        org.junit.Assume.assumeTrue("Batch controls require an isolated queue", repository.tasks.value.isEmpty())
        val previousNetwork = repository.mobileDataAllowed
        val previousParallel = withContext(Dispatchers.Main) { repository.parallelDownloads }
        try {
            withContext(Dispatchers.Main) {
                repository.setMobileDataAllowed(true)
                episodes.forEach { repository.enqueue(detail, it) }
                ids.forEach { repository.pause(it) }
            }
            await { ids.all { repository.get(it)?.download?.stopReason == OfflineDownloadRepository.STOP_REASON_USER } }
            ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                withContext(Dispatchers.Main) { AppGraph.downloadRepository.awaitLoaded() }
                scenario.onActivity { it.findViewById<View>(R.id.parallelButton).performClick() }
                clickDialogChoice("3 个")
                scenario.onActivity {
                    assertEquals(3, repository.manager.maxParallelDownloads)
                    it.findViewById<View>(R.id.startAllButton).performClick()
                }
                await { ids.all { repository.get(it)?.download?.stopReason == Download.STOP_REASON_NONE } }
                suspend fun awaitBatchLabel(label: Int) = await {
                    var matches = false
                    scenario.onActivity {
                        val button = it.findViewById<android.widget.Button>(R.id.startAllButton)
                        matches = button.isEnabled && button.text == it.getString(label)
                    }
                    matches
                }
                awaitBatchLabel(R.string.download_pause_all)
                suspend fun verifyEpisodeFits() = await {
                    var verified = false
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                        val first = list.getChildAt(0) ?: return@onActivity
                        val title = first.findViewById<android.widget.TextView>(R.id.title)
                        val episode = first.findViewById<android.widget.TextView>(R.id.subtitle)
                        val statuses = (0 until list.childCount).map { list.getChildAt(it).findViewById<android.widget.TextView>(R.id.status) }
                        verified = title.layout?.getEllipsisCount(0)?.let { it > 0 } == true &&
                            episode.width > 0 && episode.layout?.getEllipsisCount(0) == 0 &&
                            statuses.any { it.text == "排队中" && it.currentTextColor == activity.getColor(R.color.download_queued_text) }
                    }
                    verified
                }
                verifyEpisodeFits()
                screenshot("downloads-queue-controls.png")
                scenario.onActivity { it.findViewById<View>(R.id.startAllButton).performClick() }
                await { ids.all { repository.get(it)?.download?.stopReason == OfflineDownloadRepository.STOP_REASON_USER } }
                awaitBatchLabel(R.string.download_start_all)
                // Individually resuming a single task leaves the mixed queue in start-all mode.
                withContext(Dispatchers.Main) { repository.resume(ids.first()) }
                await { repository.get(ids.first())?.download?.stopReason == Download.STOP_REASON_NONE }
                awaitBatchLabel(R.string.download_start_all)
                scenario.onActivity { it.findViewById<View>(R.id.startAllButton).performClick() }
                awaitBatchLabel(R.string.download_pause_all)
                scenario.onActivity {
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                    assertTrue(list.getChildAt(0).performLongClick())
                    assertEquals(it.getString(R.string.download_remove_count, 1), it.findViewById<android.widget.TextView>(R.id.removeSelected).text)
                    assertFalse(it.findViewById<View>(R.id.backButton).isShown)
                    it.findViewById<View>(R.id.selectAll).performClick()
                }
                scenario.recreate()
                withContext(Dispatchers.Main) { AppGraph.downloadRepository.awaitLoaded() }
                scenario.onActivity {
                    assertTrue(it.findViewById<com.google.android.material.button.MaterialButton>(R.id.selectAll).isChecked)
                    assertTrue(it.findViewById<View>(R.id.removeSelected).isEnabled)
                }
                verifyEpisodeFits()
                screenshot("downloads-queue-edit.png")
                scenario.onActivity { it.findViewById<View>(R.id.removeSelected).performClick() }
                clickDialogChoice("取消") { screenshot("downloads-delete-confirmation.png") }
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(R.id.removeSelected).isEnabled)
                    assertTrue(ids.all { id -> repository.get(id) != null })
                    it.findViewById<View>(R.id.removeSelected).performClick()
                }
                clickDialogChoice("删除")
                await { ids.none { repository.get(it) != null } }
            }
        } finally {
            withContext(Dispatchers.Main) {
                ids.filter { repository.get(it) != null }.forEach { repository.remove(it) }
                repository.setParallelDownloads(previousParallel)
                repository.setMobileDataAllowed(previousNetwork)
            }
            gate.cancel()
        }
    }

    @Test
    fun pausePersistsAndTimeoutPreservesManualPauseAndRemovalCleansIndex() = runBlocking {
        val server = FixtureServer(instrumentation.context, segmentDelayMs = 350)
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode) = episode
            })
        }
        val previousNetwork = repository.mobileDataAllowed
        val episode = Episode("paused", "暂停测试", 0, server.url("/download/master.m3u8"))
        val detail = VideoDetail("offline-pause-fixture", "下载状态验证", "", 1, episodes = listOf(episode))
        val id = downloadId(detail.mediaKey, episode.episodeKey)
        try {
            withContext(Dispatchers.Main) { repository.setMobileDataAllowed(true); repository.enqueue(detail, episode) }
            await { (repository.get(id)?.bytesDownloaded ?: 0) > 0 }
            withContext(Dispatchers.Main) { repository.pause(id) }
            await { repository.get(id)?.download?.stopReason == OfflineDownloadRepository.STOP_REASON_USER }
            assertEquals(OfflineDownloadRepository.STOP_REASON_USER, withContext(Dispatchers.IO) { repository.manager.downloadIndex.getDownload(id)!!.stopReason })
            withContext(Dispatchers.Main) { repository.pauseForTimeout() }
            delay(250)
            assertEquals(OfflineDownloadRepository.STOP_REASON_USER, withContext(Dispatchers.IO) { repository.manager.downloadIndex.getDownload(id)!!.stopReason })
            ActivityScenario.launch(DownloadsActivity::class.java).use {
                instrumentation.waitForIdleSync()
                screenshot("downloads-paused.png")
            }
            withContext(Dispatchers.Main) { repository.resume(id) }
            await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
            withContext(Dispatchers.Main) { repository.remove(id) }
            assertNull(withContext(Dispatchers.IO) { repository.manager.downloadIndex.getDownload(id) })
        } finally {
            withContext(Dispatchers.Main) { repository.remove(id); repository.setMobileDataAllowed(previousNetwork) }
            server.close()
        }
    }

    @Test(timeout = 120_000)
    fun failedSegmentsRetryWithFreshUrlAndLiveSourcesFailWithExplicitReason() = runBlocking {
        val server = FixtureServer(instrumentation.context)
        var freshUrl = server.url("/download/aes/index.m3u8?version=old")
        var resolveFails = false
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode): Episode {
                    if (resolveFails) throw java.io.IOException("unavailable")
                    return episode.copy(mediaUrl = freshUrl)
                }
            })
        }
        val previousNetwork = repository.mobileDataAllowed
        val episode = Episode("retry", "失败重试验证", 0, freshUrl)
        val detail = VideoDetail("offline-errors-fixture", "下载错误验证", "", 1, episodes = listOf(episode))
        val id = downloadId(detail.mediaKey, episode.episodeKey)
        try {
            withContext(Dispatchers.Main) { repository.setMobileDataAllowed(true) }
            server.rejectLaterSegments = true
            withContext(Dispatchers.Main) { repository.enqueue(detail, episode) }
            await { repository.get(id)?.download?.state == Download.STATE_FAILED }
            assertEquals(com.aiyifan.app.core.download.DownloadFailure.EXPIRED, repository.get(id)?.failure)
            resolveFails = true
            withContext(Dispatchers.Main) { repository.retry(id) }
            await { repository.get(id)?.download?.state == Download.STATE_FAILED }
            assertEquals(com.aiyifan.app.core.download.DownloadFailure.PREPARATION, repository.get(id)?.failure)
            resolveFails = false
            server.rejectLaterSegments = false
            freshUrl = server.url("/download/aes/index.m3u8?version=new")
            withContext(Dispatchers.Main) { repository.retry(id) }
            await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
            assertEquals(freshUrl, withContext(Dispatchers.Main) { repository.offlineRequest(id).uri.toString() })
            withContext(Dispatchers.Main) { repository.remove(id) }
            freshUrl = server.url("/download/live.m3u8")
            withContext(Dispatchers.Main) { repository.enqueue(detail, episode) }
            await { repository.get(id)?.download?.state == Download.STATE_FAILED }
            assertEquals(com.aiyifan.app.core.download.DownloadFailure.LIVE, repository.get(id)?.failure)
        } finally {
            withContext(Dispatchers.Main) { repository.remove(id); repository.setMobileDataAllowed(previousNetwork) }
            server.close()
        }
    }

    @Test(timeout = 60_000)
    fun allowingOneMobileTaskDoesNotReleaseTheOther() = runBlocking<Unit> {
        val server = FixtureServer(instrumentation.context)
        val resolved = CopyOnWriteArrayList<String>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode): Episode {
                    resolved += episode.episodeKey
                    finish.await()
                    return episode
                }
            })
        }
        val previous = repository.mobileDataAllowed
        val episodes = (1..2).map { Episode("consent-$it", "第 ${it} 集", it, server.url("/playback-test.mp4")) }
        val detail = VideoDetail("consent-fixture", "移动网络测试", "", 1, episodes = episodes)
        val ids = episodes.map { downloadId(detail.mediaKey, it.episodeKey) }
        try {
            withContext(Dispatchers.Main) { repository.setMobileDataAllowed(false) }
            org.junit.Assume.assumeTrue(repository.needsMobileConsent(ids))
            val before = repository.storageBytes().first
            withContext(Dispatchers.Main) { episodes.forEach { repository.enqueue(detail, it) } }
            ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                var chosen = ""
                await {
                    scenario.onActivity {
                        val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                        val row = list.getChildAt(0) ?: return@onActivity
                        val subtitle = row.findViewById<android.widget.TextView>(R.id.subtitle).text.toString()
                        chosen = episodes.single { episode -> subtitle.startsWith(episode.episodeTitle) }.episodeKey
                        row.performClick()
                    }
                    chosen.isNotEmpty()
                }
                val id = downloadId(detail.mediaKey, chosen)
                await { repository.get(id)?.download?.stopReason == OfflineDownloadRepository.STOP_REASON_USER }
                scenario.onActivity {
                    it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler).getChildAt(0).performClick()
                }
                clickDialogChoice("仅本次允许") { screenshot("download-mobile-sheet.png") }
                await { chosen in resolved }
                scenario.onActivity { it.findViewById<View>(R.id.startAllButton).performClick() }
                clickDialogChoice("仅 Wi-Fi 下载") {
                    instrumentation.runOnMainSync {
                        val summaries = androidx.test.platform.view.inspector.WindowInspectorCompat.getGlobalWindowViews()
                            .mapNotNull { it.findViewById<android.widget.TextView>(R.id.selectionSummary) }
                        assertTrue(summaries.any { it.text == context.getString(R.string.download_data_unknown, 1) })
                    }
                }
                assertFalse(repository.needsMobileConsent(listOf(id)))
                finish.complete(Unit)
                await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
                assertEquals(listOf(chosen), resolved.toList())
                assertFalse(repository.mobileDataAllowed)
                val other = ids.single { it != id }
                assertEquals(com.aiyifan.app.core.download.STOP_REASON_WIFI, repository.get(other)?.download?.stopReason)
                assertTrue(repository.storageBytes().first > before)
                assertTrue(repository.needsMobileConsent(listOf(id)))
                scenario.onActivity { it.findViewById<View>(R.id.startAllButton).performClick() }
                clickDialogChoice("始终允许")
                await { repository.get(other)?.download?.state == Download.STATE_COMPLETED }
                assertTrue(repository.mobileDataAllowed)
            }
        } finally {
            withContext(Dispatchers.Main) {
                ids.filter { repository.get(it) != null }.forEach { repository.remove(it) }
                repository.setMobileDataAllowed(previous)
            }
            server.close()
        }
    }

    @Test(timeout = 30_000)
    fun queuePersistsWithoutRequestsUntilPermittedNetworkAndCanBeDeletedWhileWaiting() = runBlocking {
        val policy = com.aiyifan.app.core.download.DownloadNetworkPolicy(context)
        val requirements = androidx.media3.exoplayer.scheduler.Requirements(androidx.media3.exoplayer.scheduler.Requirements.NETWORK)
        // The first half needs a restricted transport; after consent Media3 needs a validated network.
        org.junit.Assume.assumeTrue(policy.needsConsent && requirements.getNotMetRequirements(context) == 0)
        val server = FixtureServer(instrumentation.context)
        var resolves = 0
        val repository = withContext(Dispatchers.Main) {
            OfflineDownloadRepository(context, object : CatalogRepository by FakeCatalogRepository() {
                override suspend fun resolveDownload(detail: VideoDetail, episode: Episode): Episode { resolves++; return episode }
            })
        }
        val previousNetwork = repository.mobileDataAllowed
        val episode = Episode("network", "等待 Wi-Fi", 0, server.url("/download/master.m3u8"))
        val detail = VideoDetail("offline-network-fixture", "网络限制", "", 1, episodes = listOf(episode))
        val id = downloadId(detail.mediaKey, episode.episodeKey)
        try {
            withContext(Dispatchers.Main) { repository.setMobileDataAllowed(false) }
            withTimeout(3_000) { repository.enqueue(detail, episode) }
            delay(1200)
            assertEquals(0, resolves)
            assertTrue(server.requests.isEmpty())
            assertNotNull(withContext(Dispatchers.IO) { repository.manager.downloadIndex.getDownload(id) })
            withContext(Dispatchers.Main) { AppGraph.downloadRepository.awaitLoaded() }
            assertNotNull(AppGraph.downloadRepository.get(id))
            assertEquals(-1f, repository.get(id)!!.percentDownloaded, 0f)
            assertEquals(id, repository.enqueue(detail, episode))
            withContext(Dispatchers.Main) { repository.remove(id) }
            assertNull(withContext(Dispatchers.IO) { repository.manager.downloadIndex.getDownload(id) })
            assertEquals(0, resolves)
            withContext(Dispatchers.Main) { repository.enqueue(detail, episode); repository.setMobileDataAllowed(true) }
            await { repository.get(id)?.download?.state == Download.STATE_COMPLETED }
            assertEquals(1, resolves)
        } finally {
            withContext(Dispatchers.Main) { repository.remove(id); repository.setMobileDataAllowed(previousNetwork) }
            server.close()
        }
    }

    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(100)
    }

    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "download-validation/$name")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}

/** A range-capable loopback source. It never proxies external URLs. */
private class FixtureServer(private val assetsContext: Context, private val segmentDelayMs: Long = 0) : Closeable {
    private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile private var closed = false
    @Volatile var rejectLaterSegments = false
    init {
        workers.execute {
            while (!closed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                workers.execute { runCatching { serve(client) }; runCatching { client.close() } }
            }
        }
    }
    fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
        }
        requests += path
        if (rejectLaterSegments && path.endsWith("index2.ts")) {
            client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        if (path.endsWith(".ts") && segmentDelayMs > 0) Thread.sleep(segmentDelayMs)
        val bytes = if (path == "/download/live.m3u8") {
            assetsContext.assets.open("download/high/index.m3u8").bufferedReader().use { it.readText() }
                .replace("#EXT-X-ENDLIST", "").replace("index", "high/index").toByteArray()
        } else assetsContext.assets.open(path.removePrefix("/")).use { it.readBytes() }
        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
        val type = when { path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; path.endsWith(".ts") -> "video/mp2t"; path.endsWith(".mp4") -> "video/mp4"; else -> "application/octet-stream" }
        val status = if (range == null) "200 OK" else "206 Partial Content"
        val headers = buildString {
            append("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes, start, end - start + 1); flush() }
    }
    override fun close() { closed = true; runCatching { socket.close() }; workers.shutdownNow() }
}
