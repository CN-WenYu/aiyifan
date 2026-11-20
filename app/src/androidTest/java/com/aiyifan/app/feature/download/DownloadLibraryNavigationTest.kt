@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.feature.download

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.download.DownloadMetadata
import com.aiyifan.app.core.download.DownloadRuntime
import com.aiyifan.app.core.download.downloadId
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.main.MainActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadLibraryNavigationTest {
    @Test fun longPressAndBatchDeletionKeepEpisodesAtTheSameScrollPosition() = runBlocking<Unit> {
        val repo = withContext(Dispatchers.Main) { AppGraph.downloadRepository.also { it.awaitLoaded() } }
        val index = repo.manager.downloadIndex as WritableDownloadIndex
        val episodes = (1..40).map { Episode("episode-$it", "第 $it 集", it, null) }
        val film = VideoDetail("library-delete-fixture", "下载管理验证", "", 1, episodes = episodes)
        val positions = 12..15
        val ids = positions.map { downloadId(film.mediaKey, episodes[it].episodeKey) }
        val previous = StartupSession.mode
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val snapshots = withContext(Dispatchers.Main) { DownloadRuntime.get(instrumentation.targetContext).snapshots }
        fun <T : View> sheetView(id: Int): T? =
            WindowInspectorCompat.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<T>(id) }
        fun capture(name: String) {
            // Wait for window transitions to finish before capturing the rendered state.
            SystemClock.sleep(400)
            val image = instrumentation.uiAutomation.takeScreenshot()
            try {
                File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { image.recycle() }
        }
        try {
            snapshots.saveFilm(film)
            positions.zip(ids).forEach { (position, id) ->
                val request = DownloadRequest.Builder(id, Uri.parse("file:///fixture.mp4"))
                    .setData(DownloadMetadata(film, episodes[position], position, generation = "fixture").encode())
                    .build()
                val bytes = 128_000_000L
                val progress = DownloadProgress().apply { bytesDownloaded = bytes }
                index.putDownload(Download(request, Download.STATE_COMPLETED, Long.MAX_VALUE, 2L, bytes,
                    0, Download.FAILURE_REASON_NONE, progress))
            }
            repo.awaitLoaded()
            StartupSession.mode = AppMode.ONLINE
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                suspend fun awaitUi(stage: String, condition: (MainActivity) -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 8_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        var matches = false
                        scenario.onActivity { matches = condition(it) }
                        if (matches) return
                        delay(50)
                    }
                    throw AssertionError("Timed out waiting for $stage")
                }
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_DOWNLOADS)!!.select() }
                awaitUi("film card") { it.findViewById<RecyclerView>(R.id.films).findViewHolderForAdapterPosition(0) != null }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.films).findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                awaitUi("episode grid") { sheetView<RecyclerView>(R.id.episodes)?.childCount?.let { it > 0 } == true }
                capture("download-library-manage-entry.png")
                fun button(id: Int) = sheetView<TextView>(id)!!
                scenario.onActivity {
                    button(R.id.primaryButton).performClick()
                    assertEquals(it.getString(R.string.download_library_manage_title), button(R.id.sheetTitle).text)
                    assertFalse(button(R.id.primaryButton).isEnabled)
                    button(R.id.manageDoneButton).performClick()
                }
                lateinit var grid: RecyclerView
                lateinit var layout: GridLayoutManager
                scenario.onActivity {
                    grid = sheetView<RecyclerView>(R.id.episodes)!!
                    layout = grid.layoutManager as GridLayoutManager
                    layout.scrollToPositionWithOffset(12, 0)
                }
                awaitUi("scrolled episode grid") { grid.findViewHolderForAdapterPosition(12) != null }
                fun row(position: Int) = grid.findViewHolderForAdapterPosition(position)!!.itemView
                var anchor = 0
                var offset = 0
                scenario.onActivity {
                    anchor = layout.findFirstVisibleItemPosition()
                    offset = layout.findViewByPosition(anchor)!!.top
                    assertTrue("Long press must enter management", row(12).performLongClick())
                    assertEquals(it.getString(R.string.download_library_delete_count, 1), button(R.id.primaryButton).text)
                }
                awaitUi("selectable neighboring episode") { grid.findViewHolderForAdapterPosition(13)?.itemView?.isClickable == true }
                scenario.onActivity {
                    row(13).performClick()
                    assertEquals(it.getString(R.string.download_library_delete_count, 2), button(R.id.primaryButton).text)
                    button(R.id.primaryButton).performClick()
                }
                clickDialogChoice("取消")
                scenario.onActivity {
                    assertEquals(it.getString(R.string.download_library_delete_count, 2), button(R.id.primaryButton).text)
                    assertTrue(ids.all { repo.get(it)?.download?.state == Download.STATE_COMPLETED })
                    row(13).performClick()
                    button(R.id.primaryButton).performClick()
                }
                clickDialogChoice("删除")
                awaitUi("episode removed in place") {
                    sheetView<RecyclerView>(R.id.episodes) === grid && repo.get(ids.first()) == null &&
                        row(12).findViewById<TextView>(R.id.episodeState).text == it.getString(R.string.download_library_not_downloaded)
                }
                scenario.onActivity {
                    assertEquals(anchor, layout.findFirstVisibleItemPosition())
                    assertEquals(offset, layout.findViewByPosition(anchor)!!.top)
                    assertFalse(row(12).isEnabled)
                    assertFalse(button(R.id.primaryButton).isEnabled)
                }
                capture("download-library-delete-disabled.png")
                scenario.onActivity {
                    val all = button(R.id.selectAllButton)
                    all.performClick()
                    assertEquals(it.getString(R.string.download_library_delete_count, 3), button(R.id.primaryButton).text)
                    all.performClick()
                    assertFalse(button(R.id.primaryButton).isEnabled)
                    all.performClick()
                }
                capture("download-library-delete-selected.png")
                scenario.onActivity { button(R.id.primaryButton).performClick() }
                clickDialogChoice("删除")
                awaitUi("last downloads removed") {
                    ids.all { repo.get(it) == null } && it.findViewById<RecyclerView>(R.id.films).adapter?.itemCount == 0
                }
                scenario.onActivity {
                    assertTrue(grid.isShown)
                    assertEquals(40, grid.adapter?.itemCount)
                    assertEquals(anchor, layout.findFirstVisibleItemPosition())
                    assertEquals(offset, layout.findViewByPosition(anchor)!!.top)
                    assertFalse(button(R.id.selectAllButton).isEnabled)
                    button(R.id.manageDoneButton).performClick()
                    assertEquals(it.getString(R.string.download_library_episodes), button(R.id.sheetTitle).text)
                    assertFalse(button(R.id.primaryButton).isEnabled)
                }
                capture("download-library-manage-empty.png")
            }
        } finally {
            ids.forEach(index::removeDownload)
            snapshots.removeFilm(film.mediaKey)
            repo.awaitLoaded()
            StartupSession.mode = previous
        }
    }

    @Test fun downloadSettingsReflectQueueChangesAfterReturning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousMode = StartupSession.mode
        StartupSession.mode = AppMode.ONLINE
        try {
            ActivityScenario.launch(MainActivity::class.java).use { mine ->
                mine.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select() }
                instrumentation.waitForIdleSync()
                val repo = AppGraph.downloadRepository
                val previousCount = repo.parallelDownloads
                val previousMobile = repo.mobileDataAllowed
                fun capture(name: String) {
                    instrumentation.waitForIdleSync()
                    val image = instrumentation.uiAutomation.takeScreenshot()
                    try {
                        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { image.recycle() }
                }
                try {
                    mine.onActivity {
                        val mobile = it.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.mobileDownloadSwitch)
                        val page = generateSequence(mobile.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
                        page.scrollTo(0, page.getChildAt(0).height)
                        mobile.performClick()
                        assertTrue(repo.mobileDataAllowed != previousMobile)
                    }
                    instrumentation.uiAutomation.waitForIdle(200, 3000)
                    capture("download-settings.png")
                    mine.onActivity { it.findViewById<View>(R.id.downloadCountRow).performClick() }
                    clickDialogChoice("1 个") { capture("download-count-dialog.png") }
                    mine.onActivity {
                        assertTrue(repo.parallelDownloads == 1)
                        assertTrue(it.findViewById<TextView>(R.id.downloadCountValue).text == "1 个")
                    }
                    ActivityScenario.launch(DownloadsActivity::class.java).use { queue ->
                        queue.onActivity {
                            val button = it.findViewById<TextView>(R.id.parallelButton)
                            assertTrue(button.text == it.getString(R.string.download_parallel, 1))
                            button.performClick()
                        }
                        clickDialogChoice("3 个")
                    }
                    ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                        reopened.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select() }
                        instrumentation.waitForIdleSync()
                        reopened.onActivity {
                            assertTrue(it.findViewById<TextView>(R.id.downloadCountValue).text == "3 个")
                            assertTrue(it.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.mobileDownloadSwitch).isChecked != previousMobile)
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        repo.setParallelDownloads(previousCount)
                        repo.setMobileDataAllowed(previousMobile)
                    }
                }
            }
        } finally { StartupSession.mode = previousMode }
    }

    @Test fun libraryReflectsPersistedEpisodesAcrossStatesAndRecreation() = runBlocking<Unit> {
        val repo = withContext(Dispatchers.Main) { AppGraph.downloadRepository.also { it.awaitLoaded() } }
        assumeTrue("Requires an empty library", repo.tasks.value.isEmpty())
        val index = repo.manager.downloadIndex as WritableDownloadIndex
        val episodes = (1..2).map { Episode("library-$it", "第 ${it} 集", it, null) }
        val film = VideoDetail("library-fixture", "山海之间", "", 1, episodes = episodes)
        val previous = StartupSession.mode
        // Seed the same persisted index used after a process restart. No transfer or network is needed.
        suspend fun record(position: Int, completed: Boolean) {
            val ep = episodes[position]
            val request = DownloadRequest.Builder(downloadId(film.mediaKey, ep.episodeKey), Uri.parse("file:///fixture.mp4"))
                .setData(DownloadMetadata(film, ep, position, generation = "fixture").encode()).build()
            val state = if (completed) Download.STATE_COMPLETED else Download.STATE_STOPPED
            val bytes = 128_000_000L
            val progress = DownloadProgress().apply { bytesDownloaded = if (completed) bytes else 0L }
            index.putDownload(Download(request, state, 1L, 2L, bytes, if (completed) 0 else 1, Download.FAILURE_REASON_NONE, progress))
            repo.awaitLoaded()
        }
        try {
            StartupSession.mode = AppMode.ONLINE
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                suspend fun awaitScene(queue: Boolean, completed: Int) {
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    var matches = false
                    while (!matches && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            val list = activity.findViewById<RecyclerView>(R.id.films)
                            matches = list.adapter?.itemCount == (if (completed > 0) 1 else 0) &&
                                activity.findViewById<View>(R.id.queueEntry).isShown == queue &&
                                activity.findViewById<View>(R.id.emptyState).isShown == (completed == 0) &&
                                activity.findViewById<View>(R.id.browseHome).isShown == (!queue && completed == 0) &&
                                (completed == 0 || list.getChildAt(0)?.findViewById<TextView>(R.id.summary)
                                    ?.text?.contains("已下载 ${completed} 集") == true)
                        }
                        if (!matches) delay(50)
                    }
                    assertTrue("Expected queue=$queue, completed=$completed", matches)
                }
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_DOWNLOADS)!!.select() }
                awaitScene(false, 0)
                record(0, false)
                record(1, false)
                awaitScene(true, 0)
                record(0, true)
                awaitScene(true, 1)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.waitForIdleSync()
                val image = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(instrumentation.targetContext.cacheDir, "downloads-mixed.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { image.recycle() }
                scenario.onActivity {
                    val row = it.findViewById<RecyclerView>(R.id.films).getChildAt(0)
                    assertTrue(row.performLongClick())
                    assertTrue(!it.findViewById<View>(R.id.queueEntry).isShown)
                    assertTrue(it.findViewById<TextView>(R.id.removeSelected).text == it.getString(R.string.download_remove_count, 1))
                }
                scenario.recreate()
                awaitScene(false, 1)
                val editImage = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(instrumentation.targetContext.cacheDir, "downloads-library-edit.png").outputStream().use {
                        editImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { editImage.recycle() }
                scenario.onActivity {
                    it.findViewById<View>(R.id.selectAll).performClick()
                    assertTrue(!it.findViewById<View>(R.id.removeSelected).isEnabled)
                    it.findViewById<View>(R.id.selectAll).performClick()
                    it.findViewById<View>(R.id.removeSelected).performClick()
                }
                clickDialogChoice("取消")
                scenario.onActivity { it.findViewById<View>(R.id.removeSelected).performClick() }
                clickDialogChoice("删除")
                awaitScene(true, 0)
                assertTrue(repo.get(downloadId(film.mediaKey, episodes[1].episodeKey)) != null)
                record(0, true)
                record(1, true)
                awaitScene(false, 2)
                scenario.recreate()
                awaitScene(false, 2)
            }
        } finally {
            episodes.forEach { index.removeDownload(downloadId(film.mediaKey, it.episodeKey)) }
            repo.awaitLoaded()
            StartupSession.mode = previous
        }
    }
}
