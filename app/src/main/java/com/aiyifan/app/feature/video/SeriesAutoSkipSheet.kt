package com.aiyifan.app.feature.video

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.createAppBottomSheet
import com.aiyifan.app.core.ui.setAppSheetContent
import com.aiyifan.app.databinding.SheetSeriesAutoSkipBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

internal object SeriesAutoSkipSheet {
    fun show(
        context: Context,
        times: SeriesSkipTimes,
        onSave: (SeriesSkipTimes) -> Unit,
    ): BottomSheetDialog {
        val sheet = context.createAppBottomSheet()
        val binding = SheetSeriesAutoSkipBinding.inflate(LayoutInflater.from(sheet.context))
        ViewCompat.setAccessibilityHeading(binding.title, true)
        bindTime(binding.introMinutes, binding.introSeconds, times.introSeconds)
        bindTime(binding.outroMinutes, binding.outroSeconds, times.outroSeconds)
        binding.saveButton.setOnClickListener {
            val intro = readTime(binding.introMinutes, binding.introSeconds)
            val outro = readTime(binding.outroMinutes, binding.outroSeconds)
            if (intro != null && outro != null) {
                onSave(SeriesSkipTimes(intro, outro))
                sheet.dismiss()
            }
        }
        binding.resetButton.setOnClickListener {
            onSave(SeriesSkipTimes())
            sheet.dismiss()
        }
        sheet.setAppSheetContent(binding.root)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        sheet.show()
        return sheet
    }

    private fun bindTime(minutes: EditText, seconds: EditText, totalSeconds: Long) {
        if (totalSeconds > 0L) {
            minutes.setText((totalSeconds / 60L).toString())
            seconds.setText((totalSeconds % 60L).toString())
        }
    }

    private fun readTime(minutes: EditText, seconds: EditText): Long? {
        val minutesValue = readNumber(minutes, 999L, R.string.series_skip_minutes_error)
        val secondsValue = readNumber(seconds, 59L, R.string.series_skip_seconds_error)
        return if (minutesValue != null && secondsValue != null) minutesValue * 60L + secondsValue else null
    }

    private fun readNumber(field: EditText, max: Long, error: Int): Long? {
        val value = field.text.toString().trim().ifEmpty { "0" }.toLongOrNull()?.takeIf { it in 0L..max }
        field.error = if (value == null) field.context.getString(error) else null
        return value
    }
}
