package com.aiyifan.app.feature.mine

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aiyifan.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class HeaderImageMaskTest {
    @Test fun `single taps toggle mask double tap does not and recreation restores state`() {
        val controller = openHeaderImageActivity()
        fun mask() = controller.get().findViewById<View>(R.id.header_image_mask)
        fun image() = controller.get().selectedZoomImage()
        fun tap() {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 20 else 0,
                    action, 100f, 100f, 0).also { image().dispatchTouchEvent(it); it.recycle() }
            }
        }
        try {
            val download = controller.get().findViewById<View>(R.id.header_image_download)
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
        val fresh = openHeaderImageActivity()
        try { assertEquals(View.VISIBLE, fresh.get().findViewById<View>(R.id.header_image_mask).visibility) }
        finally { fresh.pause().stop().destroy() }
    }

    @Test fun `viewer opens at the chosen image and restores the new position after recreation`() {
        val controller = openHeaderImageActivity(HeaderImageSource.localNames, initialPosition = 1)
        try {
            assertEquals(3, controller.get().imagePager().adapter!!.itemCount)
            assertEquals(1, controller.get().imagePager().currentItem)
            controller.get().imagePager().setCurrentItem(2, false)
            controller.recreate()
            assertEquals(2, controller.get().imagePager().currentItem)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `second tap immediately locks a zoomed gesture to the image`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = object : FrameLayout(context) {
            var disallowsPaging = false
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                disallowsPaging = disallowIntercept
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }
        }
        val image = ZoomImageView(context)
        parent.addView(image)
        image.layout(0, 0, 400, 600)
        image.setImageBitmap(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))

        fun action(type: Int, downAt: Long, eventAt: Long = downAt) {
            MotionEvent.obtain(downAt, eventAt, type, 200f, 300f, 0).also {
                image.dispatchTouchEvent(it)
                it.recycle()
            }
        }

        val first = SystemClock.uptimeMillis()
        action(MotionEvent.ACTION_DOWN, first)
        assertFalse(parent.disallowsPaging)
        action(MotionEvent.ACTION_UP, first, first + 20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        val second = SystemClock.uptimeMillis()
        action(MotionEvent.ACTION_DOWN, second)
        assertTrue(parent.disallowsPaging)
        action(MotionEvent.ACTION_UP, second, second + 20)
        assertFalse(parent.disallowsPaging)
    }
}
