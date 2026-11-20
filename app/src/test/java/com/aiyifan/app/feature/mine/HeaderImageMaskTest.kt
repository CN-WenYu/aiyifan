package com.aiyifan.app.feature.mine

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.aiyifan.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class HeaderImageMaskTest {
    @Test fun `single taps toggle mask double tap does not and recreation restores state`() {
        val controller = openHeaderImageSheet()
        fun mask() = controller.get().imageSheet().requireView().findViewWithTag<View>("header_image_mask")
        fun image() = controller.get().imageSheet().requireView().findViewWithTag<ZoomImageView>("header_zoom_image")
        fun tap() {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 20 else 0,
                    action, 100f, 100f, 0).also { image().dispatchTouchEvent(it); it.recycle() }
            }
        }
        try {
            val download = controller.get().imageSheet().requireView().findViewWithTag<View>("header_image_download")
            assertNotNull("Image preview needs a save button", download)
            assertEquals(controller.get().resources.getDimensionPixelSize(R.dimen.dp_48), download.layoutParams.width)
            assertTrue(download.contentDescription.isNotBlank())
            assertNotNull(mask())
            assertEquals(View.VISIBLE, mask().visibility)
            assertFalse(mask().isClickable)
            tap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(View.GONE, mask().visibility)
            tap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(View.VISIBLE, mask().visibility)
            tap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            tap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(View.VISIBLE, mask().visibility)
            image().performClick()
            assertEquals(View.GONE, mask().visibility)
            controller.recreate()
            assertEquals(View.GONE, mask().visibility)
        } finally { controller.pause().stop().destroy() }
        val fresh = openHeaderImageSheet()
        try { assertEquals(View.VISIBLE, fresh.get().imageSheet().requireView().findViewWithTag<View>("header_image_mask").visibility) }
        finally { fresh.pause().stop().destroy() }
    }
}
