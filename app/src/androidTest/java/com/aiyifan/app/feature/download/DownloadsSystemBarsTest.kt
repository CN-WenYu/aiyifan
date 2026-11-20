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
import com.aiyifan.app.feature.main.MainActivity
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadsSystemBarsTest {
    @Test fun libraryAndQueueUseTheirHostSystemBarColors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val previousSession = StartupSession.mode
        try {
            for (light in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    StartupSession.mode = AppMode.ONLINE
                    AppCompatDelegate.setDefaultNightMode(if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                }
                for (library in listOf(true, false)) {
                    val target = if (library) MainActivity::class.java else DownloadsActivity::class.java
                    ActivityScenario.launch(target).use { scenario ->
                        val frame = CountDownLatch(1)
                        scenario.onActivity {
                            if (library) it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_DOWNLOADS)!!.select()
                            val decor = it.window.decorView
                            decor.postOnAnimation { decor.postOnAnimation { frame.countDown() } }
                        }
                        assertTrue("Tab switch should be drawn before capture", frame.await(3, TimeUnit.SECONDS))
                        instrumentation.uiAutomation.waitForIdle(400, 5_000)
                        val image = instrumentation.uiAutomation.takeScreenshot()
                        try {
                            val theme = if (light) "light" else "dark"
                            val name = if (library) "library" else "queue"
                            File(instrumentation.targetContext.cacheDir, "downloads-$theme-$name.png").outputStream().use {
                                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            scenario.onActivity { activity ->
                                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                                    .getInsets(WindowInsetsCompat.Type.systemBars())
                                fun hasBackground(y: Int, color: Int) =
                                    (1..9).count { image.getPixel(image.width * it / 10, y) == color } >= 4
                                if (insets.top > 0) {
                                    assertTrue("Status bar should match the header (library=$library, light=$light, sample=${Integer.toHexString(image.getPixel(image.width / 2, insets.top / 2))})", hasBackground(insets.top / 2, activity.getColor(R.color.surface)))
                                }
                                if (insets.bottom > 0) {
                                    val background = if (library) activity.getColor(R.color.surface) else if (light && Build.VERSION.SDK_INT < 26) Color.BLACK else activity.getColor(R.color.page_bg)
                                    assertTrue("Navigation bar should match the list (library=$library) (light=$light)",
                                        hasBackground(image.height - insets.bottom / 2 - 1, background))
                                }
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
