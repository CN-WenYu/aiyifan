package com.aiyifan.app.core.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.ui.PlayerView
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.feature.localmedia.LocalVideoPlayerActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class FullscreenLockInteractionTest {
    @Test fun `online unlock prompt hides on timeout and repeated taps`() = withPlayer(false) { checkPromptHiding(it) }
    @Test fun `local unlock prompt hides on timeout and repeated taps`() = withPlayer(true) { checkPromptHiding(it) }

    private fun checkPromptHiding(fixture: Fixture) {
        fixture.lock.performClick()
        fixture.overlay.performClick()
        assertEquals(View.VISIBLE, fixture.unlock.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3000))
        assertEquals("Unlock icon must not stay on the video", View.GONE, fixture.unlock.visibility)
        assertEquals(View.VISIBLE, fixture.overlay.visibility)
        assertFalse(fixture.player.useController)
        repeat(3) {
            fixture.overlay.performClick()
            assertEquals(View.VISIBLE, fixture.unlock.visibility)
            fixture.overlay.performClick()
            assertEquals(View.GONE, fixture.unlock.visibility)
        }
        fixture.overlay.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        fixture.unlock.performClick()
        fixture.lock.performClick()
        fixture.overlay.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000))
        assertEquals("Previous lock timer must be cancelled", View.VISIBLE, fixture.unlock.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        assertEquals(View.GONE, fixture.unlock.visibility)
        fixture.overlay.performClick()
        fixture.activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3000))
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertTrue(fixture.player.useController)
    }

    @Test fun `online player consumes touches and requires a separate unlock tap`() = withPlayer(false) { checkLockInteraction(it) }
    @Test fun `local player consumes touches and requires a separate unlock tap`() = withPlayer(true) { checkLockInteraction(it) }

    @Test fun `online back exits full screen and restores controller input`() = withPlayer(false) { checkBack(it) }
    @Test fun `local back exits full screen and restores controller input`() = withPlayer(true) { checkBack(it) }

    @Test fun `online stop clears the lock`() = withPlayer(false) { fixture ->
        fixture.lock.performClick()
        fixture.activity.javaClass.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(fixture.activity)
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertTrue(fixture.player.useController)
    }

    @Test fun `local stop clears the lock`() = withPlayer(true) { fixture ->
        fixture.lock.performClick()
        fixture.activity.javaClass.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(fixture.activity)
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertTrue(fixture.player.useController)
    }

    @Test fun `pip round trip cannot restore a locked controller`() = withPlayer(false) { fixture ->
        fixture.lock.performClick()
        (fixture.activity as VideoPlayerActivity).onPictureInPictureModeChanged(true)
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertFalse(fixture.player.useController)
        fixture.activity.onPictureInPictureModeChanged(false)
        assertTrue(fixture.player.useController)
        assertEquals(View.GONE, fixture.overlay.visibility)
    }

    @Test fun `portrait configuration clears local full screen lock`() = withPlayer(true) { fixture ->
        fixture.lock.performClick()
        (fixture.activity as LocalVideoPlayerActivity).onConfigurationChanged(
            Configuration(fixture.activity.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT },
        )
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertEquals(View.GONE, fixture.lock.visibility)
        assertTrue(fixture.player.useController)
    }

    private fun checkLockInteraction(fixture: Fixture) {
        var playerTouches = 0
        fixture.player.setOnTouchListener { _, _ -> playerTouches++; true }
        fixture.lock.performClick()
        assertFalse("Media3 must not automatically show controls while locked", fixture.player.useController)
        assertEquals(View.GONE, fixture.topBar.visibility)
        assertEquals(View.VISIBLE, fixture.overlay.visibility)
        assertEquals(View.GONE, fixture.unlock.visibility)
        fixture.player.showController()
        assertEquals(View.GONE, fixture.topBar.visibility)
        val root = fixture.overlay.parent as View
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 600)
        assertTrue("Overlay bounds: ${fixture.overlay.width} x ${fixture.overlay.height}, root: ${root.width} x ${root.height}", fixture.overlay.width >= 1000 && fixture.overlay.height >= 600)
        tap(root, 500f, 300f)
        assertEquals(0, playerTouches)
        assertEquals(View.VISIBLE, fixture.unlock.visibility)
        assertEquals(View.VISIBLE, fixture.overlay.visibility)
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 600)
        tap(root, fixture.unlock.x + fixture.unlock.width / 2f, fixture.unlock.y + fixture.unlock.height / 2f)
        assertEquals(0, playerTouches)
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertTrue(fixture.player.useController)
        assertEquals(View.VISIBLE, fixture.topBar.visibility)
    }

    private fun checkBack(fixture: Fixture) {
        fixture.lock.performClick()
        fixture.activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(fixture.activity.isFinishing)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, fixture.activity.requestedOrientation)
        assertEquals(View.GONE, fixture.overlay.visibility)
        assertTrue(fixture.player.useController)
    }

    private fun tap(view: View, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, time, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun withPlayer(local: Boolean, test: (Fixture) -> Unit) {
        com.aiyifan.app.core.startup.StartupSession.mode = if (local) com.aiyifan.app.core.startup.AppMode.LOCAL else com.aiyifan.app.core.startup.AppMode.ONLINE
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val type = if (local) LocalVideoPlayerActivity::class.java else VideoPlayerActivity::class.java
        val intent = Intent().putExtra("contentUri", "content://media/external/video/media/1").putExtra("id", 1L)
        val controller = Robolectric.buildActivity(type, intent)
        val activity = controller.get()
        shadowOf(activity.application).grantPermissions("${activity.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        activity.setTheme(R.style.AppTheme)
        AppGraph.initialize(activity)
        controller.create()
        (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
        val player = activity.findViewById<PlayerView>(if (local) R.id.localPlayerView else R.id.playerView)
        // No network or decoder is needed for input dispatch and presentation behavior.
        player.player?.stop()
        controller.visible()
        val fixture = Fixture(
            activity, player,
            activity.findViewById(if (local) R.id.localFullScreenLockButton else R.id.fullScreenLockButton),
            activity.findViewById(if (local) R.id.localLockOverlay else R.id.fullScreenLockOverlay),
            activity.findViewById(if (local) R.id.localUnlockButton else R.id.fullScreenUnlockButton),
            activity.findViewById(if (local) R.id.localPlayerTopBar else R.id.fullScreenTitleBar),
        )
        try {
            activity.findViewById<View>(if (local) R.id.localFullScreenButton else R.id.fullScreenButton).performClick()
            player.showController()
            test(fixture)
        } finally {
            activity.finish()
            controller.destroy()
            com.aiyifan.app.core.startup.StartupSession.mode = null
        }
    }

    private data class Fixture(val activity: AppCompatActivity, val player: PlayerView, val lock: View, val overlay: View, val unlock: View, val topBar: View)
}
