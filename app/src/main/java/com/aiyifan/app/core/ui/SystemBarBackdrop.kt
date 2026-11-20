package com.aiyifan.app.core.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

internal class SystemBarBackdrop(
    private val pageColor: Int,
    private val navigationColor: Int,
) : Drawable() {
    private val paint = Paint()
    private var status = Insets.NONE
    private var navigation = Insets.NONE
    var showStatusBackdrop = true
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    fun update(insets: WindowInsetsCompat) {
        val newStatus = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val newNavigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        if (status == newStatus && navigation == newNavigation) return
        status = newStatus
        navigation = newNavigation
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (showStatusBackdrop) drawEdges(canvas, status, pageColor)
        drawEdges(canvas, navigation, navigationColor)
    }

    private fun drawEdges(canvas: Canvas, insets: Insets, color: Int) {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()
        paint.color = color
        if (insets.top > 0) canvas.drawRect(left, top, right, top + insets.top, paint)
        if (insets.bottom > 0) canvas.drawRect(left, bottom - insets.bottom, right, bottom, paint)
        if (insets.left > 0) canvas.drawRect(left, top, left + insets.left, bottom, paint)
        if (insets.right > 0) canvas.drawRect(right - insets.right, top, right, bottom, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
