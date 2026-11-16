package com.aiyifan.app.feature.download

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadSelectionSheetTest {
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun addingOnMobileShowsWifiMessageAndKeepsTasksAfterSheetCloses() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requirements = androidx.media3.exoplayer.scheduler.Requirements(androidx.media3.exoplayer.scheduler.Requirements.NETWORK_UNMETERED)
        org.junit.Assume.assumeTrue(requirements.getNotMetRequirements(context) != 0)
        val repository = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.aiyifan.app.core.data.AppGraph.downloadRepository }
        val previous = repository.mobileDataAllowed
        val episodes = (1..2).map { Episode("waiting-$it", "%02d".format(it), it, null) }
        val detail = VideoDetail("waiting-sheet-fixture", "等待 Wi-Fi 验证", "", 1, episodes = episodes)
        var sheet: BottomSheetDialog? = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { repository.setMobileDataAllowed(false) }
            ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                scenario.onActivity { sheet = DownloadSelectionDialog.show(it, detail) }
                kotlinx.coroutines.withTimeout(10_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity { ready = sheet!!.findViewById<MaterialButton>(R.id.selectAllButton)!!.isEnabled }
                        if (ready) break
                        kotlinx.coroutines.delay(50)
                    }
                }
                scenario.onActivity {
                    sheet!!.findViewById<MaterialButton>(R.id.selectAllButton)!!.performClick()
                    sheet!!.findViewById<MaterialButton>(R.id.primaryButton)!!.performClick()
                }
                kotlinx.coroutines.withTimeout(10_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity {
                            ready = sheet!!.findViewById<android.widget.TextView>(R.id.statusTitle)!!.text.toString()
                                .contains("等待 Wi-Fi")
                        }
                        if (ready) break
                        kotlinx.coroutines.delay(50)
                    }
                }
                saveScreenshot("download-sheet-waiting-wifi.png")
                scenario.onActivity { sheet!!.findViewById<MaterialButton>(R.id.primaryButton)!!.performClick() }
                assertFalse(sheet!!.isShowing)
                assertFalse(repository.mobileDataAllowed)
                assertEquals(2, repository.tasks.value.count { it.metadata?.detail?.mediaKey == detail.mediaKey })
                assertTrue(repository.tasks.value.filter { it.metadata?.detail?.mediaKey == detail.mediaKey }
                    .all { it.download.state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED })
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                sheet?.dismiss()
                episodes.forEach { repository.remove(com.aiyifan.app.core.download.downloadId(detail.mediaKey, it.episodeKey)) }
                repository.setMobileDataAllowed(previous)
            }
        }
    }

    @Test
    fun bottomSheetKeepsActionVisibleAndSelectionMatchesGrid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val detail = VideoDetail("sheet-fixture", "谷雨", "", 1,
            episodes = (1..40).map { Episode("sheet-$it", "%02d".format(it), it, mediaUrl = null) })
        var sheet: BottomSheetDialog? = null
        ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { sheet = DownloadSelectionDialog.show(it, detail) }
                val deadline = System.currentTimeMillis() + 10_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        ready = sheet!!.findViewById<MaterialButton>(R.id.selectAllButton)!!.isEnabled &&
                            sheet!!.findViewById<RecyclerView>(R.id.episodes)!!.childCount > 0
                    }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Download index and grid must be ready", ready)
                scenario.onActivity {
                    val picker = sheet!!
                    val grid = picker.findViewById<RecyclerView>(R.id.episodes)!!
                    val primary = picker.findViewById<MaterialButton>(R.id.primaryButton)!!
                    assertFalse(primary.isEnabled)
                    val first = grid.findViewHolderForAdapterPosition(0)!!.itemView
                    val second = grid.findViewHolderForAdapterPosition(1)!!.itemView
                    first.performClick()
                    second.performClick()
                    assertEquals(it.getString(R.string.download_start_count, 2), primary.text.toString())
                    assertTrue(primary.isEnabled)
                    val sheetTop = IntArray(2)
                    picker.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!.getLocationOnScreen(sheetTop)
                    assertTrue("Sheet must leave the top of the page visible", sheetTop[1] > 0)
                }
                Thread.sleep(350) // Wait for the sheet entrance animation before the visual check.
                saveScreenshot("download-sheet-selected.png")
                scenario.onActivity {
                    val picker = sheet!!
                    val all = picker.findViewById<MaterialButton>(R.id.selectAllButton)!!
                    val primary = picker.findViewById<MaterialButton>(R.id.primaryButton)!!
                    all.performClick()
                    assertEquals(it.getString(R.string.download_start_count, 40), primary.text.toString())
                    all.performClick()
                    assertFalse(primary.isEnabled)
                    picker.findViewById<RecyclerView>(R.id.episodes)!!.scrollToPosition(39)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val primary = sheet!!.findViewById<MaterialButton>(R.id.primaryButton)!!
                    assertTrue(primary.getGlobalVisibleRect(android.graphics.Rect()))
                    sheet!!.findViewById<View>(R.id.closeButton)!!.performClick()
                    assertFalse(sheet!!.isShowing)
                }
            } finally {
                scenario.onActivity { sheet?.dismiss() }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getExternalFilesDir(null), "download-validation/$name")
        file.parentFile!!.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
