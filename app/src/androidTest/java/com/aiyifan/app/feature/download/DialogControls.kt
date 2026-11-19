package com.aiyifan.app.feature.download

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat

internal fun clickDialogChoice(text: String, beforeClick: () -> Unit = {}) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + 5_000
    var center: PointF? = null
    while (center == null && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync {
            val matches = ArrayList<View>()
            WindowInspectorCompat.getGlobalWindowViews().filter { it.hasWindowFocus() }.forEach {
                it.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT)
            }
            val label = matches.filterIsInstance<TextView>().firstOrNull {
                it.text.toString() == text && it.isShown && it.width > 0
            } ?: return@runOnMainSync
            val origin = IntArray(2)
            label.getLocationOnScreen(origin)
            center = PointF(origin[0] + label.width / 2f, origin[1] + label.height / 2f)
        }
        if (center == null) SystemClock.sleep(50)
    }
    val point = center ?: throw AssertionError("Missing dialog choice: $text")
    beforeClick()
    val down = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
