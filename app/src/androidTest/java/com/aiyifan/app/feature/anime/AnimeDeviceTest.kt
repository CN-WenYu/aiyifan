package com.aiyifan.app.feature.anime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.BuildConfig
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.main.MainActivity
import com.aiyifan.app.feature.search.SearchActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import com.google.android.material.tabs.TabLayout
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
    fun bottomTabRestoresExploreAndOpensAnimeSearch() {
        assumeTrue(BuildConfig.ANIME_ENABLED)
        val monitor = instrumentation.addMonitor(SearchActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectExplore(scenario)
                scenario.recreate()
                scenario.onActivity { activity ->
                    val tabs = activity.findViewById<TabLayout>(R.id.bottomTabs)
                    assertEquals(2, tabs.selectedTabPosition)
                    assertTrue(activity.findViewById<View>(R.id.animeSearch).performClick())
                }
                val search = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 6_000L))
                instrumentation.runOnMainSync {
                    val hint = search.findViewById<EditText>(R.id.searchEdit).hint
                    assertEquals(context.getString(R.string.anime_search_hint), hint.toString())
                    search.finish()
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun liveExploreShowsBothFeedsAndOpensScheduleRankingAndContinueWatching() {
        assumeTrue(BuildConfig.ANIME_ENABLED)
        assumeTrue(InstrumentationRegistry.getArguments().getString("animeExploreLive") == "true")
        val originalMode = AppCompatDelegate.getDefaultNightMode()
        val episode = Episode("bangumi:90000002", "1 · 正片", 90000002, null)
        AppGraph.catalogRepository.saveHistory(
            VideoDetail(TEST_MEDIA_KEY, "追番播放验证", "", 2, episodes = listOf(episode)),
            episode,
            150_000L,
            1_200_000L,
        )
        try {
            setNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectExplore(scenario)
                awaitExploreFeed(scenario)
                screenshot("anime-explore-light")
                verifyPosterNavigation(scenario)
                verifySchedule(scenario)
                verifyRanking(scenario)
                verifyRecommendationPaging(scenario)
                verifyContinueWatching(scenario)
            }
            setNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectExplore(scenario)
                awaitExploreFeed(scenario)
                screenshot("anime-explore-dark")
            }
        } finally {
            setNightMode(originalMode)
        }
    }

    private fun selectExplore(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val tabs = activity.findViewById<TabLayout>(R.id.bottomTabs)
            assertEquals(5, tabs.tabCount)
            assertEquals(context.getString(R.string.anime_entry), tabs.getTabAt(2)?.text)
            tabs.getTabAt(2)?.select()
        }
    }

    private fun awaitExploreFeed(scenario: ActivityScenario<MainActivity>) {
        await("both exploration feeds") {
            var loaded = false
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.animeExploreList)
                val trending = list.findViewById<RecyclerView>(R.id.animeTrendingList)
                loaded = (trending?.adapter?.itemCount ?: 0) in 1..10 && (list.adapter?.itemCount ?: 0) > 2
                if (loaded) {
                    val grid = list.layoutManager as GridLayoutManager
                    assertTrue(grid.spanCount >= 2)
                }
            }
            loaded
        }
        await("visible trending and recommended posters") {
            var loaded = false
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.animeExploreList)
                val trending = list.findViewById<RecyclerView>(R.id.animeTrendingList)
                val featured = trending.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<ImageView>(R.id.animePoster)
                val recommended = list.findViewHolderForAdapterPosition(1)?.itemView?.findViewById<ImageView>(R.id.animePoster)
                loaded = featured?.drawable is BitmapDrawable && recommended?.drawable is BitmapDrawable
            }
            loaded
        }
    }

    private fun verifySchedule(scenario: ActivityScenario<MainActivity>) {
        withBrowsePage(scenario, R.id.animeSchedule, R.string.anime_airing_schedule) { browse ->
            await("today selected in the airing schedule") {
                onMain {
                    val days = browse.findViewById<TabLayout>(R.id.animeScheduleDays)
                    val position = days.selectedTabPosition
                    val visibleTab = (days.getChildAt(0) as? ViewGroup)?.getChildAt(position)
                    days.isShown && days.getTabAt(position)?.text?.startsWith("今天") == true &&
                        visibleTab?.getGlobalVisibleRect(Rect()) == true
                }
            }
            screenshot("anime-airing")
        }
    }

    private fun verifyRanking(scenario: ActivityScenario<MainActivity>) {
        withBrowsePage(scenario, R.id.animeRanking, R.string.anime_ranking) { browse ->
            val list = browse.findViewById<RecyclerView>(R.id.animeBrowseList)
            try {
                await("ranking starts on the highest ranked title") {
                    onMain {
                        val first = list.findViewHolderForAdapterPosition(0)
                        first?.itemView?.findViewById<TextView>(R.id.animeBrowseRowMeta)
                            ?.text?.startsWith("第 1 名") == true
                    }
                }
            } catch (error: IllegalStateException) {
                screenshot("anime-ranking-failure")
                throw error
            }
            screenshot("anime-ranking")
            verifyNextPage(list, "ranking")
        }
    }

    private fun verifyRecommendationPaging(scenario: ActivityScenario<MainActivity>) {
        var exploreList: RecyclerView? = null
        scenario.onActivity { exploreList = it.findViewById(R.id.animeExploreList) }
        val list = checkNotNull(exploreList)
        verifyNextPage(list, "recommendation")
        instrumentation.runOnMainSync { list.scrollToPosition(0) }
        await("continue watching row back in view") { onMain { list.findViewById<View>(R.id.animeContinueList) != null } }
    }

    private fun verifyNextPage(list: RecyclerView, name: String) {
        await("first $name page laid out") {
            onMain { (list.adapter?.itemCount ?: 0) >= 24 && list.canScrollVertically(1) }
        }
        var initialCount = 0
        instrumentation.runOnMainSync {
            initialCount = checkNotNull(list.adapter).itemCount
            list.smoothScrollToPosition(initialCount - 1)
        }
        var lastVisible = -1
        var currentCount = 0
        try {
            await("second $name page") {
                onMain {
                    lastVisible = (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                    currentCount = list.adapter?.itemCount ?: 0
                    currentCount > initialCount
                }
            }
        } catch (error: IllegalStateException) {
            throw AssertionError("$name did not append: count=$currentCount, initial=$initialCount, lastVisible=$lastVisible", error)
        }
    }

    private fun verifyPosterNavigation(scenario: ActivityScenario<MainActivity>) {
        val monitor = instrumentation.addMonitor(VideoPlayerActivity::class.java.name, null, false)
        try {
            scenario.onActivity {
                val trending = it.findViewById<RecyclerView>(R.id.animeTrendingList)
                assertTrue(checkNotNull(trending.findViewHolderForAdapterPosition(0)).itemView.performClick())
            }
            val player = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 6_000L))
            instrumentation.runOnMainSync {
                assertTrue(player.intent.getStringExtra("mediaKey")?.startsWith("bangumi:") == true)
                player.finish()
            }
            await("return from trending details") { onMain { player.isDestroyed } }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun withBrowsePage(
        scenario: ActivityScenario<MainActivity>,
        action: Int,
        title: Int,
        checkPage: (AnimeBrowseActivity) -> Unit,
    ) {
        val monitor = instrumentation.addMonitor(AnimeBrowseActivity::class.java.name, null, false)
        try {
            scenario.onActivity { assertTrue(it.findViewById<View>(action).performClick()) }
            val browse = instrumentation.waitForMonitorWithTimeout(monitor, 6_000L) as? AnimeBrowseActivity
                ?: error("Explore did not open its browse page")
            try {
                instrumentation.runOnMainSync {
                    assertEquals(context.getString(title), browse.findViewById<TextView>(R.id.animeBrowseTitle).text)
                }
                checkPage(browse)
            } finally {
                instrumentation.runOnMainSync { browse.finish() }
                await("return to Explore") { onMain { browse.isDestroyed } }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun verifyContinueWatching(scenario: ActivityScenario<MainActivity>) {
        val monitor = instrumentation.addMonitor(VideoPlayerActivity::class.java.name, null, false)
        try {
            scenario.onActivity {
                val history = it.findViewById<RecyclerView>(R.id.animeContinueList)
                val card = checkNotNull(history.findViewHolderForAdapterPosition(0))
                assertEquals("追番播放验证", card.itemView.findViewById<TextView>(R.id.title).text)
                assertTrue(card.itemView.performClick())
            }
            val player = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 6_000L))
            instrumentation.runOnMainSync {
                assertEquals(TEST_MEDIA_KEY, player.intent.getStringExtra("mediaKey"))
                player.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun setNightMode(mode: Int) {
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
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
