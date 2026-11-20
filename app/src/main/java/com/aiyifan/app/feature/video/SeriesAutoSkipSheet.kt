package com.aiyifan.app.feature.video

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.createAppBottomSheet
import com.aiyifan.app.core.ui.setAppSheetContent
import com.aiyifan.app.databinding.SheetSeriesAutoSkipBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

internal object SeriesAutoSkipSheet {
    fun show(
        context: Context,
        times: SeriesSkipTimes,
        markers: SeriesSkipMarkers,
        onSave: (SeriesSkipTimes) -> Unit,
    ): BottomSheetDialog {
        val sheet = context.createAppBottomSheet()
        val binding = SheetSeriesAutoSkipBinding.inflate(LayoutInflater.from(sheet.context))
        ViewCompat.setAccessibilityHeading(binding.title, true)
        bindTime(binding.introMinutes, binding.introSeconds, times.introSeconds)
        bindTime(binding.outroMinutes, binding.outroSeconds, times.outroSeconds)
        fun renderSummaries() {
            binding.introSummary.text = summary(context, readTime(binding.introMinutes, binding.introSeconds))
            binding.outroSummary.text = summary(context, readTime(binding.outroMinutes, binding.outroSeconds))
        }
        renderSummaries()
        listOf(binding.introMinutes, binding.introSeconds, binding.outroMinutes, binding.outroSeconds).forEach {
            it.doAfterTextChanged { renderSummaries() }
        }
        binding.markIntroButton.isEnabled = markers.introSeconds != null
        binding.markIntroButton.setOnClickListener {
            markers.introSeconds?.let { bindTime(binding.introMinutes, binding.introSeconds, it) }
        }
        binding.markOutroButton.isEnabled = markers.outroSeconds != null
        binding.markOutroButton.setOnClickListener {
            markers.outroSeconds?.let { bindTime(binding.outroMinutes, binding.outroSeconds, it) }
        }
        binding.markUnavailable.isVisible = markers.introSeconds == null && markers.outroSeconds == null

        fun showManualFields(visible: Boolean) {
            binding.manualFields.isVisible = visible
            binding.manualToggle.setText(if (visible) R.string.series_skip_hide_manual else R.string.series_skip_show_manual)
        }
        binding.manualToggle.setOnClickListener {
            val expand = !binding.manualFields.isVisible
            if (!expand) {
                sheet.window?.let { WindowCompat.getInsetsController(it, binding.root).hide(WindowInsetsCompat.Type.ime()) }
            }
            showManualFields(expand)
        }
        binding.saveButton.setOnClickListener {
            val intro = readTime(binding.introMinutes, binding.introSeconds, showError = true)
            val outro = readTime(binding.outroMinutes, binding.outroSeconds, showError = true)
            if (intro != null && outro != null) {
                onSave(SeriesSkipTimes(intro, outro))
                sheet.dismiss()
            } else {
                showManualFields(true)
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
        minutes.error = null
        seconds.error = null
    }

    private fun summary(context: Context, seconds: Long?): String = when (seconds) {
        null -> context.getString(R.string.series_skip_invalid_value)
        0L -> context.getString(R.string.series_skip_default_value)
        else -> context.getString(R.string.series_skip_time_value, seconds / 60L, seconds % 60L)
    }

    private fun readTime(minutes: EditText, seconds: EditText, showError: Boolean = false): Long? {
        val minutesValue = readNumber(minutes, SeriesSkipMarkers.MAX_MINUTES)
        val secondsValue = readNumber(seconds, 59L)
        if (showError) {
            minutes.error = if (minutesValue == null) minutes.context.getString(R.string.series_skip_minutes_error) else null
            seconds.error = if (secondsValue == null) seconds.context.getString(R.string.series_skip_seconds_error) else null
        }
        return if (minutesValue != null && secondsValue != null) minutesValue * 60L + secondsValue else null
    }

    private fun readNumber(field: EditText, max: Long): Long? =
        field.text.toString().trim().ifEmpty { "0" }.toLongOrNull()?.takeIf { it in 0L..max }
}
