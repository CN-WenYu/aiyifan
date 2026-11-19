package com.aiyifan.app.feature.localmedia

import android.graphics.Bitmap
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalMediaDeviceTest {
    @Test fun localRootKeepsHeaderAndContentInsideSystemBarsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        try {
            for (night in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync {
                    StartupSession.mode = AppMode.LOCAL
                    AppCompatDelegate.setDefaultNightMode(night)
                }
                ActivityScenario.launch(LocalMediaActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    var topInset = 0
                    var bottomInset = 0
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(R.id.localMediaContainer)
                        val header = activity.findViewById<View>(R.id.localHeader)
                        val insets = ViewCompat.getRootWindowInsets(root)!!
                            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        topInset = insets.top
                        bottomInset = insets.bottom
                        assertEquals(0, root.paddingTop)
                        assertEquals(0, root.paddingBottom)
                        assertEquals(insets.top, header.paddingTop)
                        val expectedHeaderHeight = (56 * activity.resources.displayMetrics.density).toInt() + insets.top
                        assertTrue("Header height should include its top inset", kotlin.math.abs(expectedHeaderHeight - header.height) <= 1)
                        assertNull(activity.findViewById<View>(R.id.localBackButton))
                        val list = activity.findViewById<View>(R.id.localRecycler)
                        val location = IntArray(2)
                        val content = list.parent as View
                        content.getLocationOnScreen(location)
                        assertEquals("Content should end above the navigation bar",
                            root.height - insets.bottom, location[1] + content.height)
                        assertTrue(activity.isTaskRoot)
                    }
                    val name = if (night == AppCompatDelegate.MODE_NIGHT_YES) "local-dark.png" else "local-light.png"
                    val file = File(instrumentation.targetContext.getExternalFilesDir(null), "startup-validation/$name")
                    file.parentFile!!.mkdirs()
                    instrumentation.uiAutomation.waitForIdle(400, 5_000)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        try {
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val x = bitmap.width / 3
                            if (topInset > 0) {
                                assertEquals("Status bar must blend into the header",
                                    bitmap.getPixel(x, topInset + 2), bitmap.getPixel(x, topInset / 2))
                            }
                            if (bottomInset > 0 && (android.os.Build.VERSION.SDK_INT >= 26 || night == AppCompatDelegate.MODE_NIGHT_YES)) {
                                assertEquals("Navigation bar must blend into the page",
                                    bitmap.getPixel(x, bitmap.height - bottomInset - 2),
                                    bitmap.getPixel(x, bitmap.height - bottomInset / 2))
                            }
                        }
                        finally { bitmap.recycle() }
                    }
                    scenario.onActivity {
                        it.onBackPressedDispatcher.onBackPressed()
                        assertTrue("Local media root should finish on system back", it.isFinishing)
                    }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(previousMode)
                StartupSession.mode = null
            }
        }
    }
}
