package com.aiyifan.app.feature.video

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.createOptionsSheet
import com.google.android.material.bottomsheet.BottomSheetDialog

object SleepTimerDialog {
    fun label(context: Context, state: SleepTimerState): String = when (state.option) {
        SleepTimerOption.OFF -> context.getString(R.string.sleep_timer_title)
        SleepTimerOption.END_OF_EPISODE -> context.getString(R.string.sleep_timer_end_label)
        else -> {
            val seconds = ((state.remainingMs ?: 0L) + 999L) / 1_000L
            context.getString(R.string.sleep_timer_countdown, seconds / 60L, seconds % 60L)
        }
    }

    fun show(context: Context, controller: VideoPlaybackController, windowType: Int? = null): BottomSheetDialog {
        val themedContext = if (windowType == null) context else ContextThemeWrapper(context, R.style.AppTheme)
        val labels = intArrayOf(R.string.sleep_timer_off, R.string.sleep_timer_15, R.string.sleep_timer_30,
            R.string.sleep_timer_60, R.string.sleep_timer_end)
        val options = SleepTimerOption.entries
        val dialog = themedContext.createOptionsSheet(
            label(context, controller.sleepTimerState),
            labels.map(context::getString),
            selectedIndex = controller.sleepTimerState.option.ordinal,
            closeLabel = R.string.sleep_timer_close,
        ) { index -> controller.setSleepTimer(options[index]) }
        windowType?.let { dialog.window?.setType(it) }
        val removeListener = controller.addStateListener {
            dialog.findViewById<TextView>(R.id.title)?.text = label(context, controller.sleepTimerState)
        }
        dialog.setOnDismissListener { removeListener() }
        try {
            dialog.show()
        } catch (error: RuntimeException) {
            removeListener()
            throw error
        }
        return dialog
    }
}
