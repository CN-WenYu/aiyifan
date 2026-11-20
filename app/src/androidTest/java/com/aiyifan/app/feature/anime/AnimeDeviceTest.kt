package com.aiyifan.app.feature.anime

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.main.MainActivity
import com.aiyifan.app.feature.search.SearchActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun enableOnlineSession() {
        StartupSession.mode = AppMode.ONLINE
    }

    @After
    fun clearOnlineSession() {
        instrumentation.runOnMainSync { AppGraph.videoPlaybackController.release() }
        AppGraph.catalogRepository.removeHistory(setOf(TEST_MEDIA_KEY))
        StartupSession.mode = null
    }

    @Test
    fun homeExposesAnimeSearchWithItsOwnPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val entry = activity.findViewById<View>(R.id.animeButton)
                assertEquals(View.VISIBLE, entry.visibility)
            }
            screenshot("anime-home")
        }
        ActivityScenario.launch<SearchActivity>(SearchActivity.animeIntent(context)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(context.getString(R.string.anime_search_hint), activity.findViewById<EditText>(R.id.searchEdit).hint.toString())
            }
            screenshot("anime-search")
        }
    }

    @Test
    fun animeUsesMedia3AndKeepsEpisodeProgressWithoutAnOfflineDownloadAction() {
        val clip = File(context.cacheDir, "anime-qa.mp4")
        instrumentation.context.assets.open("playback-test.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        val episode = Episode("bangumi:90000002", "1 · 正片", 90000002, clip.toURI().toString())
        val detail = VideoDetail(TEST_MEDIA_KEY, "追番播放验证", "", 2, episodes = listOf(episode))
        try {
            instrumentation.runOnMainSync {
                AppGraph.videoPlaybackController.release()
                AppGraph.videoPlaybackController.apply {
                    openVideo(detail.mediaKey)
                    assertTrue(prepare(detail, episode))
                }
            }
            ActivityScenario.launch<VideoPlayerActivity>(VideoPlayerActivity.intent(context, detail.mediaKey)).use { scenario ->
                awaitPlayback()
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.downloadButton).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.commentTitle).visibility)
                    assertEquals(episode.episodeKey, AppGraph.videoPlaybackController.activeSession?.episode?.episodeKey)
                    AppGraph.videoPlaybackController.seekBy(8_000L)
                }
                screenshot("anime-playback")
            }
            val history = AppGraph.catalogRepository.getEpisodeHistory(detail.mediaKey, episode.episodeKey)
            assertTrue((history?.progressMs ?: 0L) >= 7_000L)
        } finally {
            clip.delete()
        }
    }

    @Test
    fun liveSearchThroughPlaybackAndEpisodeSwitch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("animeLive") == "true")
        val liveKey = "bangumi:400602"
        val monitor = instrumentation.addMonitor(VideoPlayerActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<SearchActivity>(SearchActivity.animeIntent(context)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.searchEdit).setText("葬送的芙莉莲")
                    activity.findViewById<View>(R.id.searchButton).performClick()
                }
                await("Bangumi search results and cover") {
                    var found = false
                    scenario.onActivity { activity ->
                        val first = activity.findViewById<RecyclerView>(R.id.resultRecycler)
                            .findViewHolderForAdapterPosition(0)
                        found = first?.itemView?.findViewById<ImageView>(R.id.poster)?.drawable != null
                    }
                    found
                }
                screenshot("anime-live-search")
                scenario.onActivity { activity ->
                    val results = activity.findViewById<RecyclerView>(R.id.resultRecycler)
                    val first = checkNotNull(results.findViewHolderForAdapterPosition(0))
                    first.itemView.performClick()
                }
                val player = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8_000L))
                try {
                    await("First episode") {
                        onMain {
                            !AppGraph.videoPlaybackController.isLoading &&
                                player.findViewById<RecyclerView>(R.id.episodeRecycler)
                                    .findViewHolderForAdapterPosition(0) != null
                        }
                    }
                    instrumentation.runOnMainSync {
                        assertEquals(liveKey, AppGraph.videoPlaybackController.requestedMediaKey)
                        assertEquals(null, AppGraph.videoPlaybackController.activeSession)
                        player.findViewById<RecyclerView>(R.id.episodeRecycler)
                            .findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                    }
                    await("First episode start") { onMain { AppGraph.videoPlaybackController.isPlaying } }
                    instrumentation.runOnMainSync {
                        AppGraph.videoPlaybackController.seekBy(15_000L)
                        player.findViewById<View>(R.id.fullScreenButton).performClick()
                    }
                    await("Fullscreen and seek") {
                        onMain {
                            AppGraph.videoPlaybackController.currentPositionMs >= 14_000L &&
                                player.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                    }
                    screenshot("anime-live-playback")
                    instrumentation.runOnMainSync {
                        player.findViewById<View>(R.id.playerNextEpisodeButton).performClick()
                    }
                    await("Second episode start") {
                        onMain {
                            AppGraph.videoPlaybackController.isPlaying &&
                                AppGraph.videoPlaybackController.activeSession?.episode?.episodeKey == "bangumi:1227088"
                        }
                    }
                    instrumentation.runOnMainSync { AppGraph.videoPlaybackController.seekBy(20_000L) }
                    await("Saved episode progress") {
                        onMain { AppGraph.videoPlaybackController.currentPositionMs >= 19_000L }
                    }
                } finally {
                    instrumentation.runOnMainSync { player.finish() }
                }
                await("Closed player") { onMain { player.isDestroyed } }
            }
            instrumentation.runOnMainSync { AppGraph.videoPlaybackController.release() }
            val saved = AppGraph.catalogRepository.getHistory().first { it.mediaKey == liveKey }
            assertEquals("bangumi:1227088", saved.episodeKey)
            assertTrue("Saved progress was ${saved.progressMs} ms", saved.progressMs >= 19_000L)
            ActivityScenario.launch<VideoPlayerActivity>(VideoPlayerActivity.intent(context, liveKey)).use { scenario ->
                await("Resume episode and progress", timeoutMs = 40_000L) {
                    onMain {
                        val controller = AppGraph.videoPlaybackController
                        check(controller.animePlaybackFailure == null) { "Could not get a fresh URL on resume" }
                        check(!controller.hasPlaybackError) { "Media3 failed to load the resumed episode" }
                        controller.activeSession?.episode?.episodeKey == saved.episodeKey &&
                            controller.canSeek && controller.currentPositionMs >= 19_000L
                    }
                }
                scenario.onActivity {
                    assertEquals(View.GONE, it.findViewById<View>(R.id.animePlaybackRetry).visibility)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { AppGraph.videoPlaybackController.release() }
            AppGraph.catalogRepository.removeHistory(setOf(liveKey))
        }
    }

    private fun await(description: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(125L)
        }
        error("Timed out waiting for $description")
    }

    private fun onMain(condition: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = condition() }
        return result
    }

    private fun awaitPlayback() {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var playing = false
            instrumentation.runOnMainSync { playing = AppGraph.videoPlaybackController.isPlaying }
            if (playing) return
            SystemClock.sleep(125)
        }
        error("Media3 did not start the test clip")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350L)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private companion object {
        const val TEST_MEDIA_KEY = "bangumi:90000001"
    }
}
