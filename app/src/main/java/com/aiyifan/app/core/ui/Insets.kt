package com.aiyifan.app.core.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.aiyifan.app.R

fun Activity.setupEdgeToEdge() = window.setupPageSystemBars()

@Suppress("DEPRECATION")
fun Window.setupPageSystemBars() {
    val decor = decorView
    val pageColor = decor.context.getColor(R.color.page_bg)
    val useDarkIcons = usesLightSystemBarIcons(decor.resources.configuration.uiMode)
    // Before Android 8 navigation icons are always white.
    val navigationColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && useDarkIcons) {
        Color.BLACK
    } else {
        pageColor
    }
    WindowCompat.setDecorFitsSystemWindows(this, false)
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(this, decor).apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }

    // Android 15+ draws transparent system bars over the app. Paint the inset areas
    // independently of the page so headers, sheet surfaces and video can keep their colors.
    val backdrop = SystemBarBackdrop(pageColor, navigationColor)
    backdrop.setBounds(0, 0, decor.width, decor.height)
    decor.overlay.add(backdrop)
    decor.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        backdrop.setBounds(0, 0, view.width, view.height)
    }
    ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
        backdrop.update(insets)
        ViewCompat.onApplyWindowInsets(view, insets)
    }
    ViewCompat.requestApplyInsets(decor)
}

fun View.applySystemBarsPadding(
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false,
    growHeight: Boolean = false,
    shouldApply: () -> Boolean = { true },
) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    val initialHeight = layoutParams?.height ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val extraTop = if (top) bars.top else 0
        val extraBottom = if (bottom) bars.bottom else 0

        if (shouldApply()) {
            view.updatePadding(
                left = initialLeft + if (left) bars.left else 0,
                top = initialTop + extraTop,
                right = initialRight + if (right) bars.right else 0,
                bottom = initialBottom + extraBottom,
            )
        } else {
            view.setPadding(initialLeft, initialTop, initialRight, initialBottom)
        }
        if (growHeight && initialHeight > 0) {
            view.updateLayoutParams {
                height = initialHeight + extraTop + extraBottom
            }
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
