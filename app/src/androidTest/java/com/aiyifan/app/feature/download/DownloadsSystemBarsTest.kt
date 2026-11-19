package com.aiyifan.app.feature.download

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadsSystemBarsTest {
    @Test fun bothTabsUseHeaderAndPageColorsInLightAndDarkThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val previousSession = StartupSession.mode
        try {
            for (light in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    StartupSession.mode = AppMode.ONLINE
                    AppCompatDelegate.setDefaultNightMode(if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                }
                ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                    for (tab in 0..1) {
                        val frame = CountDownLatch(1)
                        scenario.onActivity {
                            val tabs = it.findViewById<TabLayout>(R.id.tabs)
                            tabs.getTabAt(tab)!!.select()
                            tabs.postOnAnimation { tabs.postOnAnimation { frame.countDown() } }
                        }
                        assertTrue("Tab switch should be drawn before capture", frame.await(3, TimeUnit.SECONDS))
                        instrumentation.uiAutomation.waitForIdle(400, 5_000)
                        val image = instrumentation.uiAutomation.takeScreenshot()
                        try {
                            scenario.onActivity { activity ->
                                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                                    .getInsets(WindowInsetsCompat.Type.systemBars())
                                fun hasBackground(y: Int, color: Int) =
                                    (1..9).count { image.getPixel(image.width * it / 10, y) == color } >= 4
                                if (insets.top > 0) {
                                    assertTrue("Status bar should match the header", hasBackground(insets.top / 2, activity.getColor(R.color.surface)))
                                }
                                if (insets.bottom > 0) {
                                    val background = if (light && Build.VERSION.SDK_INT < 26) Color.BLACK else activity.getColor(R.color.page_bg)
                                    assertTrue("Navigation bar should match the list on tab $tab (light=$light)",
                                        hasBackground(image.height - insets.bottom / 2 - 1, background))
                                }
                            }
                            val theme = if (light) "light" else "dark"
                            val name = if (tab == 0) "active" else "completed"
                            File(instrumentation.targetContext.cacheDir, "downloads-$theme-$name.png").outputStream().use {
                                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally { image.recycle() }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(previousTheme)
                StartupSession.mode = previousSession
            }
        }
    }
}
