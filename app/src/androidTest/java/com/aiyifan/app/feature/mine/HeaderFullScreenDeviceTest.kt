package com.aiyifan.app.feature.mine

import android.content.ContentUris
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.main.MainActivity
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HeaderFullScreenDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun imageDrawsBehindStatusBarAndOtherTabsRestoreTheirAppearance() {
        StartupSession.mode = AppMode.ONLINE
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select() }
                waitFor(scenario) { it.findViewById<ImageButton>(R.id.headerExpand).isEnabled }
                scenario.onActivity {
                    val decor = it.window.decorView
                    val header = it.findViewById<HeaderGestureLayout>(R.id.mineHeader)
                    val bars = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.statusBars())
                    assertTrue(bars.top > 0)
                    assertEquals(IntArray(2).also(decor::getLocationOnScreen)[1],
                        IntArray(2).also(header::getLocationOnScreen)[1])
                    assertFalse(hasDarkStatusIcons(it))
                }
                capture("mine-status-bar.png", requireWhiteStatusIcons = true)
                scenario.onActivity {
                    val header = it.findViewById<HeaderGestureLayout>(R.id.mineHeader)
                    val scroll = (header.parent as android.view.View).parent as android.widget.ScrollView
                    // Provide enough scroll range even when this emulator can nearly show every setting.
                    (scroll.getChildAt(0) as android.view.ViewGroup).addView(android.view.View(it), 1, header.height)
                    scroll.doOnNextLayout { scroll.scrollTo(0, header.bottom + 1) }
                }
                waitFor(scenario) {
                    hasDarkStatusIcons(it)
                }
                scenario.onActivity {
                    val header = it.findViewById<HeaderGestureLayout>(R.id.mineHeader)
                    val scroll = (header.parent as android.view.View).parent as android.widget.ScrollView
                    scroll.scrollTo(0, 0)
                }
                waitFor(scenario) {
                    !hasDarkStatusIcons(it)
                }
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(0)!!.select() }
                waitFor(scenario) {
                    (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO &&
                        hasDarkStatusIcons(it)
                }
            }
        } finally {
            AppCompatDelegate.setDefaultNightMode(previousMode)
            StartupSession.mode = null
        }
    }

    @Test fun pagerSupportsBothDirectionsZoomAndRestoresTheCurrentPage() {
        val intent = HeaderImageActivity.intent(instrumentation.targetContext, HeaderImageSource.localNames, 1, cacheOnly = true)
        ActivityScenario.launch<HeaderImageActivity>(intent).use { scenario ->
            awaitPage(scenario, 1)
            swipe(scenario, forward = true)
            awaitPage(scenario, 2)
            swipe(scenario, forward = true)
            awaitPage(scenario, 2)
            swipe(scenario, forward = false)
            awaitPage(scenario, 1)
            zoom(scenario)
            swipe(scenario, forward = false)
            awaitPage(scenario, 1)
            scenario.recreate()
            awaitPage(scenario, 1)
            swipe(scenario, forward = false)
            awaitPage(scenario, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                assertSavesOriginal(scenario, HeaderImageSource.localNames.first())
            }
            scenario.onActivity {
                it.findViewById<ViewPager2>(R.id.header_image_pager)
                    .findViewWithTag<ZoomImageView>("header_zoom_image").performClick()
            }
            capture("full-screen-header.png")
        }
    }

    @Test fun preloadsAlongTheSwipeDirectionAndReusesDiskCacheOffline() {
        // This test clears Glide's disk cache, so opt in on a development emulator.
        assumeTrue(InstrumentationRegistry.getArguments().getString("headerPreload") == "true")
        val context = instrumentation.targetContext
        val names = HeaderImageSource.allNames.subList(10, 18)
        Glide.get(context).clearDiskCache()
        val intent = HeaderImageActivity.intent(context, names, initialPosition = 3)
        ActivityScenario.launch<HeaderImageActivity>(intent).use { scenario ->
            awaitPage(scenario, 3)
            awaitCached(names[2])
            awaitCached(names[4])
            awaitCached(names[5])

            swipe(scenario, forward = true)
            awaitPage(scenario, 4)
            awaitCached(names[6])

            swipe(scenario, forward = false)
            awaitPage(scenario, 3)
            awaitCached(names[1])
            swipe(scenario, forward = false)
            awaitPage(scenario, 2)
            awaitCached(names[0])
        }

        val offlineIntent = HeaderImageActivity.intent(context, names.take(3), initialPosition = 1, cacheOnly = true)
        ActivityScenario.launch<HeaderImageActivity>(offlineIntent).use { scenario ->
            awaitPage(scenario, 1)
            swipe(scenario, forward = false)
            awaitPage(scenario, 0)
            swipe(scenario, forward = false)
            awaitPage(scenario, 0)
        }
    }

    private fun awaitCached(name: String) {
        val requests = Glide.with(instrumentation.targetContext.applicationContext)
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            val target = requests.downloadOnly().load(HeaderImageSource.model(name))
                .onlyRetrieveFromCache(true).submit()
            try {
                if (target.get(2, TimeUnit.SECONDS).length() > 0L) return
            } catch (_: ExecutionException) {
                // A cache miss is expected while the asynchronous preload is in flight.
            } finally {
                requests.clear(target)
            }
            SystemClock.sleep(100)
        }
        fail("No cached image before opening the page: $name")
    }

    private fun assertSavesOriginal(scenario: ActivityScenario<HeaderImageActivity>, expectedName: String) {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val gallery = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = MediaStore.Images.Media._ID
        val name = MediaStore.Images.Media.DISPLAY_NAME
        scenario.onActivity { it.findViewById<ImageButton>(R.id.header_image_download).performClick() }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var saved: android.net.Uri? = null
        while (saved == null && SystemClock.uptimeMillis() < deadline) {
            resolver.query(gallery, arrayOf(id), "$name LIKE ?",
                arrayOf(expectedName.removeSuffix(".webp") + "_%.webp"), "$id DESC")?.use {
                if (it.moveToFirst()) saved = ContentUris.withAppendedId(gallery, it.getLong(0))
            }
            if (saved == null) SystemClock.sleep(50)
        }
        val uri = requireNotNull(saved) { "The currently selected page must save to the gallery" }
        try {
            val original = context.assets.open(HeaderImageSource.assetPath(expectedName)).use { it.readBytes() }
            val actual = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(original, actual)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun awaitPage(scenario: ActivityScenario<HeaderImageActivity>, position: Int) {
        waitFor(scenario) {
            val pager = it.findViewById<ViewPager2>(R.id.header_image_pager)
            pager.currentItem == position && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                it.findViewById<ImageButton>(R.id.header_image_download).isEnabled
        }
    }

    private fun swipe(scenario: ActivityScenario<HeaderImageActivity>, forward: Boolean) {
        val bounds = IntArray(3)
        scenario.onActivity {
            val pager = it.findViewById<ViewPager2>(R.id.header_image_pager)
            val location = IntArray(2).also(pager::getLocationOnScreen)
            bounds[0] = location[0] + pager.width / 5
            bounds[1] = location[0] + pager.width * 4 / 5
            bounds[2] = location[1] + pager.height / 2
        }
        val start = if (forward) bounds[1] else bounds[0]
        val end = if (forward) bounds[0] else bounds[1]
        val y = bounds[2]
        val shell = instrumentation.uiAutomation.executeShellCommand("input swipe $start $y $end $y 250")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { it.readBytes() }
    }

    private fun zoom(scenario: ActivityScenario<HeaderImageActivity>) {
        scenario.onActivity {
            val pager = it.findViewById<ViewPager2>(R.id.header_image_pager)
            val down = SystemClock.uptimeMillis()
            val center = pager.width / 2f
            val pointerY = pager.height / 2f
            val initialRadius = pager.width * 0.05f
            val finalRadius = pager.width * 0.42f
            val recyclerView = pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
            val image = recyclerView.findViewHolderForAdapterPosition(pager.currentItem)?.itemView as ZoomImageView
            fun scale(): Float = FloatArray(9).also(image.imageMatrix::getValues)[android.graphics.Matrix.MSCALE_X]
            val initialScale = scale()
            val pointers = Array(2) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            fun send(action: Int, count: Int, radius: Float, offset: Long) {
                val coordinates = Array(count) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = center + if (index == 0) -radius else radius
                        y = pointerY
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(down, down + offset, action, count, pointers, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { pager.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 1, initialRadius, 0)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, initialRadius, 20)
            repeat(12) {
                val radius = initialRadius + (finalRadius - initialRadius) * (it + 1) / 12
                send(MotionEvent.ACTION_MOVE, 2, radius, 40 + it * 16L)
            }
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, finalRadius, 260)
            send(MotionEvent.ACTION_UP, 1, finalRadius, 280)
            assertTrue("Pinch must increase the image scale", scale() > initialScale)
        }
    }

    private fun <T : android.app.Activity> waitFor(scenario: ActivityScenario<T>, condition: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Expected viewer or status bar state was never shown")
    }

    @Suppress("DEPRECATION")
    private fun hasDarkStatusIcons(activity: MainActivity): Boolean {
        val decor = activity.window.decorView
        return if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            decor.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
        } else {
            WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightStatusBars
        }
    }

    private fun capture(name: String, requireWhiteStatusIcons: Boolean = false) {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "header-validation/$name")
        file.parentFile!!.mkdirs()
        instrumentation.waitForIdleSync()
        // The app and System UI can present on different frames.
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (requireWhiteStatusIcons) {
                val bright = (0 until bitmap.height / 30).sumOf { y ->
                    (0 until bitmap.width).count { x -> android.graphics.Color.red(bitmap.getPixel(x, y)) > 210 }
                }
                assertTrue("White system status icons must be visible in the rendered frame", bright > 20)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
