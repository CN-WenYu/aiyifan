package com.aiyifan.app.feature.mine

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aiyifan.app.R
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

internal fun openHeaderImageActivity(
    names: List<String> = listOf("mine_header_001.webp"),
    initialPosition: Int = 0,
) = Robolectric.buildActivity(
    HeaderImageActivity::class.java,
    HeaderImageActivity.intent(RuntimeEnvironment.getApplication(), names, initialPosition),
).setup().visible().apply { get().layoutImageViewport() }

internal fun HeaderImageActivity.imagePager(): ViewPager2 = findViewById(R.id.header_image_pager)

internal fun HeaderImageActivity.selectedZoomImage(): ZoomImageView {
    val pager = imagePager()
    val pages = pager.getChildAt(0) as RecyclerView
    return checkNotNull(pages.findViewHolderForAdapterPosition(pager.currentItem))
        .itemView.findViewWithTag("header_zoom_image")
}

internal fun HeaderImageActivity.layoutImageViewport() {
    val decor = window.decorView
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
    )
    decor.layout(0, 0, 800, 600)
    shadowOf(Looper.getMainLooper()).idle()
}

internal fun HeaderImageActivity.awaitImageSaveEnabled() {
    val button = findViewById<View>(R.id.header_image_download)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!button.isEnabled && System.nanoTime() < deadline) {
        Thread.sleep(10)
        shadowOf(Looper.getMainLooper()).idle()
    }
    check(button.isEnabled) { "Save did not become available for the visible image" }
}
