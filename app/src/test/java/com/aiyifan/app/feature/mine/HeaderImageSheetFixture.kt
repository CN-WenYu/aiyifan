package com.aiyifan.app.feature.mine

import androidx.appcompat.app.AppCompatActivity
import com.aiyifan.app.R
import org.robolectric.Robolectric

internal const val IMAGE_SHEET_FIXTURE_TAG = "image_sheet_fixture"

internal fun openHeaderImageSheet() = Robolectric.buildActivity(AppCompatActivity::class.java).apply {
    get().setTheme(R.style.AppTheme)
    setup().visible()
    HeaderImageSheet.newInstance("mine_header_001.webp").showNow(get().supportFragmentManager, IMAGE_SHEET_FIXTURE_TAG)
}

internal fun AppCompatActivity.imageSheet(): HeaderImageSheet =
    supportFragmentManager.findFragmentByTag(IMAGE_SHEET_FIXTURE_TAG) as HeaderImageSheet
