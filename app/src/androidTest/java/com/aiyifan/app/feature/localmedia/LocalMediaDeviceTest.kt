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
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(R.id.localMediaContainer)
                        val header = activity.findViewById<View>(R.id.localHeader)
                        val insets = ViewCompat.getRootWindowInsets(root)!!
                            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        assertEquals(0, root.paddingTop)
                        assertEquals(0, root.paddingBottom)
                        assertEquals(insets.top, header.paddingTop)
                        val expectedHeaderHeight = (56 * activity.resources.displayMetrics.density).toInt() + insets.top
                        assertTrue("Header height should include its top inset", kotlin.math.abs(expectedHeaderHeight - header.height) <= 1)
                        assertNull(activity.findViewById<View>(R.id.localBackButton))
                        val list = activity.findViewById<View>(R.id.localRecycler)
                        val expectedListBottom = (12 * activity.resources.displayMetrics.density).toInt() + insets.bottom
                        assertTrue("List bottom padding should include navigation inset", kotlin.math.abs(expectedListBottom - list.paddingBottom) <= 1)
                        assertTrue(activity.isTaskRoot)
                    }
                    val name = if (night == AppCompatDelegate.MODE_NIGHT_YES) "local-dark.png" else "local-light.png"
                    val file = File(instrumentation.targetContext.getExternalFilesDir(null), "startup-validation/$name")
                    file.parentFile!!.mkdirs()
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
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
