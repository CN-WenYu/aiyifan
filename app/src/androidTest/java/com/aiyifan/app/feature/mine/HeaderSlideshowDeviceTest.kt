package com.aiyifan.app.feature.mine

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.R
import com.aiyifan.app.feature.main.MainActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.viewpager2.widget.ViewPager2
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HeaderSlideshowDeviceTest {
    @org.junit.Before fun enableOnlineSession() { com.aiyifan.app.core.startup.StartupSession.mode = com.aiyifan.app.core.startup.AppMode.ONLINE }
    @org.junit.After fun clearOnlineSession() { com.aiyifan.app.core.startup.StartupSession.mode = null }
    private fun state(activity: MainActivity): HeaderSlideshow {
        val fragment = activity.supportFragmentManager.findFragmentByTag(MineFragment::class.java.name)!!
        return MineFragment::class.java.getDeclaredField("slideshow").apply { isAccessible = true }
            .get(fragment) as HeaderSlideshow
    }

    private fun awaitImage(scenario: ActivityScenario<MainActivity>) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { ready = it.findViewById<ImageButton>(R.id.headerExpand).isEnabled }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Header image did not load")
    }

    @Test fun pauseRecreateTabsAndViewerCloseKeepState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select()
            }
            awaitImage(scenario)
            scenario.onActivity {
                assertFalse(it.findViewById<HeaderSlideView>(R.id.headerImage).performClick())
                assertFalse(it.findViewById<HeaderSlideView>(R.id.headerNextImage).performClick())
                val header = it.findViewById<android.view.View>(R.id.mineHeader)
                val topInset = androidx.core.view.ViewCompat.getRootWindowInsets(header)!!.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                ).top
                assertEquals(it.resources.getDimensionPixelSize(R.dimen.mine_header_height) + topInset, header.height)
                it.findViewById<ImageButton>(R.id.headerPlayback).performClick()
            }
            var index = -1
            scenario.onActivity { index = state(it).index; assertTrue(state(it).paused) }
            SystemClock.sleep(5500)
            scenario.onActivity { assertEquals(index, state(it).index) }
            scenario.recreate()
            awaitImage(scenario)
            scenario.onActivity { assertEquals(index, state(it).index); assertTrue(state(it).paused) }
            repeat(3) {
                scenario.onActivity { it.findViewById<ImageButton>(R.id.headerExpand).performClick() }
                awaitViewer { viewer ->
                    assertNotNull(viewer.findViewById<ViewPager2>(R.id.header_image_pager))
                    viewer.finish()
                    true
                }
                instrumentation.waitForIdleSync()
                awaitImage(scenario)
                scenario.onActivity {
                    assertEquals(index, state(it).index)
                    assertTrue(state(it).paused)
                }
            }
            scenario.onActivity {
                assertTrue(state(it).paused)
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(0)!!.select()
                assertFalse(it.findViewById<HeaderSlideView>(R.id.headerImage).hasImage)
                assertFalse(it.findViewById<HeaderSlideView>(R.id.headerNextImage).hasImage)
            }
            SystemClock.sleep(5500)
            scenario.onActivity {
                assertEquals(index, state(it).index)
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select()
            }
            awaitImage(scenario)
            scenario.onActivity { it.findViewById<ImageButton>(R.id.headerPlayback).performClick() }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var advanced = false
            while (!advanced && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                scenario.onActivity { advanced = state(it).index != index }
            }
            scenario.onActivity {
                val current = state(it)
                val diagnostic = HeaderSlideshow::class.java.declaredFields.joinToString { field ->
                    field.isAccessible = true
                    "${field.name}=${field.get(current)}"
                }
                assertNotEquals("now=${SystemClock.uptimeMillis()} $diagnostic", index, current.index)
            }
        }
    }

    @Test fun loadsExpectedSourceAndSavesCurrentOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("headerNetwork")
        org.junit.Assume.assumeTrue(mode in setOf("online", "offline"))
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val gallery = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select()
            }
            val deadline = SystemClock.uptimeMillis() + 20_000
            var selected: String? = null
            while (selected == null && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    val name = HeaderImageSource.allNames[state(it).index]
                    val ready = it.findViewById<ImageButton>(R.id.headerExpand).isEnabled
                    if (ready && !state(it).transitioning && HeaderImageSource.isLocal(name) == (mode == "offline")) {
                        selected = name
                        it.findViewById<ImageButton>(R.id.headerPlayback).performClick()
                        it.findViewById<ImageButton>(R.id.headerExpand).performClick()
                    }
                }
                if (selected == null) SystemClock.sleep(50)
            }
            val name = requireNotNull(selected) { "No image loaded from the expected source: $mode" }
            awaitViewer { viewer ->
                val save = viewer.findViewById<ImageButton>(R.id.header_image_download)
                if (save.isEnabled) save.performClick() else false
            }
            val idColumn = android.provider.MediaStore.Images.Media._ID
            val nameColumn = android.provider.MediaStore.Images.Media.DISPLAY_NAME
            var savedUri: android.net.Uri? = null
            val saveDeadline = SystemClock.uptimeMillis() + 10_000
            while (savedUri == null && SystemClock.uptimeMillis() < saveDeadline) {
                resolver.query(gallery, arrayOf(idColumn), "$nameColumn LIKE ?",
                    arrayOf(name.removeSuffix(".webp") + "_%.webp"), "$idColumn DESC")?.use {
                    if (it.moveToFirst()) savedUri = android.content.ContentUris.withAppendedId(gallery, it.getLong(0))
                }
                if (savedUri == null) SystemClock.sleep(50)
            }
            val uri = requireNotNull(savedUri) { "Saving did not create an album image" }
            try {
                val actual = resolver.openInputStream(uri)!!.use { it.readBytes() }
                val expected = if (HeaderImageSource.isLocal(name)) {
                    context.assets.open(HeaderImageSource.assetPath(name)).use { it.readBytes() }
                } else {
                    java.net.URL(HeaderImageSource.model(name)).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                    }.getInputStream().use { it.readBytes() }
                }
                assertArrayEquals(expected, actual)
            } finally {
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun awaitViewer(action: (HeaderImageActivity) -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            var done = false
            instrumentation.runOnMainSync {
                val viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HeaderImageActivity>().firstOrNull()
                done = viewer?.let(action) == true
            }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("Full-screen image viewer did not become ready")
    }

    @Test fun originFailureFallsBackAndReentryRecovers() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("headerNetwork") == "outage")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = Glide.get(context).registry
        val defaultFactory = HttpGlideUrlLoader.Factory()
        val failures = AtomicInteger()
        val blockedOrigin = object : ModelLoaderFactory<GlideUrl, InputStream> {
            override fun build(factory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> {
                val normal = defaultFactory.build(factory)
                return object : ModelLoader<GlideUrl, InputStream> {
                    override fun handles(model: GlideUrl) = normal.handles(model)

                    override fun buildLoadData(
                        model: GlideUrl, width: Int, height: Int, options: Options,
                    ): ModelLoader.LoadData<InputStream>? {
                        if (!model.toStringUrl().startsWith("https://raw.githubusercontent.com/")) {
                            return normal.buildLoadData(model, width, height, options)
                        }
                        return ModelLoader.LoadData(model, object : DataFetcher<InputStream> {
                            override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
                                failures.incrementAndGet()
                                callback.onLoadFailed(IOException("Simulated image origin outage"))
                            }
                            override fun cleanup() = Unit
                            override fun cancel() = Unit
                            override fun getDataClass() = InputStream::class.java
                            override fun getDataSource() = DataSource.REMOTE
                        })
                    }
                }
            }
            override fun teardown() = Unit
        }

        registry.replace(GlideUrl::class.java, InputStream::class.java, blockedOrigin)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select() }
                awaitHeader(scenario) { state(it).snapshot().pool.size == 3 && HeaderImageSource.isLocal(HeaderImageSource.allNames[state(it).index]) }
                assertEquals(2, failures.get())
                registry.replace(GlideUrl::class.java, InputStream::class.java, defaultFactory)
                scenario.onActivity {
                    val tabs = it.findViewById<TabLayout>(R.id.bottomTabs)
                    tabs.getTabAt(0)!!.select()
                    tabs.getTabAt(MainActivity.TAB_MINE)!!.select()
                }
                awaitHeader(scenario) { state(it).snapshot().pool.size == 188 && !HeaderImageSource.isLocal(HeaderImageSource.allNames[state(it).index]) }
            }
        } finally {
            registry.replace(GlideUrl::class.java, InputStream::class.java, defaultFactory)
        }
    }

    private fun awaitHeader(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity {
                ready = it.findViewById<ImageButton>(R.id.headerExpand).isEnabled && condition(it)
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Expected header source did not load")
    }

    /** Opt-in long soak: 188 images x two rounds x (5s hold + 450ms transition), about 35 minutes. */
    @Test fun twoFullRoundsAtRealPlaybackSpeed() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("headerSoak") == "true",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.findViewById<TabLayout>(R.id.bottomTabs).getTabAt(MainActivity.TAB_MINE)!!.select()
            }
            awaitImage(scenario)
            var previous = -1
            val seen = mutableSetOf<Int>()
            scenario.onActivity { previous = state(it).index; seen.add(previous) }
            var changes = 0
            var lastChange = SystemClock.uptimeMillis()
            while (changes < 376) {
                SystemClock.sleep(200)
                scenario.onActivity { activity ->
                    val current = state(activity)
                    assertFalse(current.exhausted)
                    if (current.index != previous) {
                        if (seen.size == 188) seen.clear()
                        assertTrue("Repeated slide before round finished", seen.add(current.index))
                        previous = current.index
                        changes++
                        lastChange = SystemClock.uptimeMillis()
                        if (changes % 20 == 0 || changes == 376) {
                            val memory = Debug.MemoryInfo()
                            Debug.getMemoryInfo(memory)
                            Log.i("HeaderSoak", "changes=$changes pssKb=${memory.totalPss} nativeKb=${Debug.getNativeHeapAllocatedSize() / 1024}")
                        }
                    }
                    val bytes = listOf(R.id.headerImage, R.id.headerNextImage).sumOf {
                        activity.findViewById<HeaderSlideView>(it).imageAllocationBytes
                    }
                    val header = activity.findViewById<HeaderGestureLayout>(R.id.mineHeader)
                    assertTrue(bytes.toLong() <= 2L * header.width * header.expandedHeightPx * 4)
                }
                assertTrue("Slideshow stalled", SystemClock.uptimeMillis() - lastChange < 15000)
            }
        }
    }
}
