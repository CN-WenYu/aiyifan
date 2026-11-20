package com.aiyifan.app.feature.mine

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.main.MainActivity
import com.google.android.material.tabs.TabLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MineBottomSheetTest {
    @Before fun enableOnlineSession() { StartupSession.mode = AppMode.ONLINE }
    @After fun clearOnlineSession() { StartupSession.mode = null }

    @Test fun themeAndImageOpenAtTheBottomOfTheSamePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<View>(R.id.themeSettingsButton).performClick() }
            assertBottomSheet(scenario, "theme-sheet.png")
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            val deadline = System.currentTimeMillis() + 10_000
            var opened = false
            while (!opened && System.currentTimeMillis() < deadline) {
                scenario.onActivity {
                    val expand = it.findViewById<View>(R.id.headerExpand)
                    if (expand.isEnabled) opened = expand.performClick()
                }
                if (!opened) Thread.sleep(50)
            }
            assertTrue("Image preview must be available", opened)
            assertBottomSheet(scenario, "image-sheet.png", waitForImage = true)
        }
    }

    private fun assertBottomSheet(
        scenario: ActivityScenario<MainActivity>,
        screenshot: String,
        waitForImage: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + 5_000
        var visible = false
        var bounds = Rect()
        var windowBottom = 0
        while (!visible && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val sheet = WindowInspector.getGlobalWindowViews()
                    .firstNotNullOfOrNull { it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) }
                val image = sheet?.findViewWithTag<ZoomImageView>("header_zoom_image")
                visible = sheet?.getGlobalVisibleRect(bounds) == true && (!waitForImage || image?.drawable != null)
                val decor = activity.window.decorView
                windowBottom = IntArray(2).also(decor::getLocationOnScreen)[1] + decor.height
            }
            if (!visible) Thread.sleep(50)
        }
        assertTrue("A visible bottom sheet must open", visible)
        assertTrue("Part of the original page must be visible", bounds.top > 0)
        assertEquals("The sheet must meet the bottom of the window", windowBottom, bounds.bottom)
        Thread.sleep(350)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "sheet-validation/$screenshot")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
