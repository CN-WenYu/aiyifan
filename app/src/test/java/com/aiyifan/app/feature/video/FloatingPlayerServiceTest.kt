package com.aiyifan.app.feature.video

import android.app.Application
import android.os.Bundle
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.media3.ui.PlayerView
import com.aiyifan.app.core.data.FakeCatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingPlayerServiceTest {
    @Test
    fun `cancelled page request cannot attach after page recreation`() = withService { service, controller, engine, windows ->
        val request = FloatingPlayerService.startIntent(service, controller.selectionToken, object : ResultReceiver(null) {})
        assertTrue(FloatingPlayerService.clearStartRequest(request))
        service.onStartCommand(request, 0, 1)
        assertEquals(0, windows.adds)
        assertEquals(0, engine.attachCalls)
        assertFalse(controller.hasOverlayOwner)
        assertTrue(controller.playWhenReady)
    }

    @Test
    fun `old callback cannot clear the new pages request`() = withService { service, controller, _, windows ->
        val old = FloatingPlayerService.startIntent(service, controller.selectionToken, object : ResultReceiver(null) {})
        val current = FloatingPlayerService.startIntent(service, controller.selectionToken, object : ResultReceiver(null) {})
        assertFalse(FloatingPlayerService.clearStartRequest(old))
        service.onStartCommand(current, 0, 1)
        assertEquals(1, windows.adds)
        assertTrue(controller.hasOverlayOwner)
        assertTrue(FloatingPlayerService.clearStartRequest(current))
    }

    @Test
    fun `success is acknowledged only after the window and playback are owned`() = withService { service, controller, engine, windows ->
        var acknowledged = false
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                assertEquals(FloatingPlayerService.RESULT_STARTED, resultCode)
                assertEquals(1, windows.adds)
                assertEquals(1, engine.attachCalls)
                assertTrue(controller.hasOverlayOwner)
                acknowledged = true
            }
        }
        service.onStartCommand(FloatingPlayerService.startIntent(service, controller.selectionToken, receiver), 0, 1)
        assertTrue(acknowledged)
    }

    @Test
    fun `stale start request cannot steal a newer video session`() = withService { service, controller, engine, windows ->
        var result: Int? = null
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result = resultCode }
        }
        service.onStartCommand(FloatingPlayerService.startIntent(service, controller.selectionToken - 1, receiver), 0, 1)
        assertEquals(0, result)
        assertEquals(0, windows.adds)
        assertEquals(0, engine.attachCalls)
        assertFalse(controller.isReleased)
        assertTrue(controller.playWhenReady)
    }

    @Test
    fun `permission revoked before startup leaves the page session intact`() = withService { service, controller, _, windows ->
        ShadowSettings.setCanDrawOverlays(false)
        service.onStartCommand(FloatingPlayerService.intent(service), 0, 1)
        assertEquals(0, windows.adds)
        assertFalse(controller.hasOverlayOwner)
        assertFalse(controller.isReleased)
        assertTrue(controller.playWhenReady)
    }

    @Test
    fun `unprepared playback cannot create an empty overlay`() = withService(prepared = false) { service, controller, _, windows ->
        service.onStartCommand(FloatingPlayerService.intent(service), 0, 1)
        assertFalse(controller.hasOverlayOwner)
        assertEquals(0, windows.adds)
        assertFalse(controller.isReleased)
    }

    @Test
    fun `window rejection preserves page playback without attaching an invisible surface`() = withService { service, controller, engine, windows ->
        windows.rejectAdd = true
        service.onStartCommand(FloatingPlayerService.intent(service), 0, 1)
        assertEquals(1, windows.adds)
        assertFalse(controller.hasOverlayOwner)
        assertEquals(0, engine.attachCalls)
        assertTrue(controller.playWhenReady)
        assertFalse(controller.isReleased)
    }

    @Test
    fun `closing an owned overlay saves progress and releases playback once`() = withService { service, controller, engine, windows ->
        service.onStartCommand(FloatingPlayerService.intent(service), 0, 1)
        assertTrue(controller.hasOverlayOwner)
        assertEquals(1, engine.attachCalls)
        service.onDestroy()
        assertEquals(1, windows.removes)
        assertTrue(controller.isReleased)
        assertEquals(1, engine.releaseCalls)
    }

    private fun withService(
        prepared: Boolean = true,
        block: (FloatingPlayerService, VideoPlaybackController, Engine, Windows) -> Unit,
    ) {
        ShadowSettings.setCanDrawOverlays(true)
        val engine = Engine()
        val controller = VideoPlaybackController(engine, FakeCatalogRepository(), object : PlaybackSession {
            override fun release() = Unit
        })
        if (prepared) {
            val episode = Episode("test-episode", "1", 1, "https://example.com/test.mp4")
            controller.prepare(VideoDetail("test", "test", "", 1, episodes = listOf(episode)), episode)
        }
        val lifecycle = Robolectric.buildService(FloatingPlayerService::class.java).create()
        val service = lifecycle.get()
        val windows = Windows(service.getSystemService(Application.WINDOW_SERVICE) as WindowManager)
        ReflectionHelpers.setField(service, "controllerHolder", lazy { controller })
        ReflectionHelpers.setField(service, "windowManager\$delegate", lazy { windows.proxy })
        try {
            block(service, controller, engine, windows)
        } finally {
            lifecycle.destroy()
            controller.release()
        }
    }

    private class Windows(private val real: WindowManager) {
        var rejectAdd = false
        var adds = 0
        var removes = 0
        val proxy = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            when (method.name) {
                "addView" -> { adds++; if (rejectAdd) throw SecurityException("Permission revoked"); null }
                "removeView" -> { removes++; null }
                "updateViewLayout" -> null
                else -> method.invoke(real, *(args ?: emptyArray()))
            }
        } as WindowManager
    }

    private class Engine : PlaybackEngine {
        override val currentPosition = 5_000L
        override val duration = 20_000L
        override val isPlaying get() = playWhenReady
        override var playWhenReady = false
        var attachCalls = 0
        var releaseCalls = 0
        override fun setMediaUrl(mediaUrl: String) = Unit
        override fun prepare() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun addPositionListener(listener: (Long) -> Unit): () -> Unit = {}
        override fun play() { playWhenReady = true }
        override fun pause() { playWhenReady = false }
        override fun attach(playerView: PlayerView) { attachCalls++ }
        override fun detach() = Unit
        override fun release() { releaseCalls++ }
    }
}
