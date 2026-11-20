package com.aiyifan.app.core.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.aiyifan.app.R
import com.aiyifan.app.databinding.SheetOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

internal fun Context.createAppBottomSheet(): BottomSheetDialog =
    BottomSheetDialog(this, R.style.ThemeOverlay_Aiyifan_BottomSheet).apply {
        behavior.apply {
            maxWidth = (640 * resources.displayMetrics.density).roundToInt()
            skipCollapsed = true
            setShouldRemoveExpandedCorners(false)
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

internal fun BottomSheetDialog.setAppSheetContent(content: View) {
    setContentView(content)
    content.applySystemBarsPadding(left = true, right = true, bottom = true)
}

internal fun Context.createOptionsSheet(
    title: CharSequence,
    options: List<CharSequence>,
    selectedIndex: Int? = null,
    @StringRes closeLabel: Int? = null,
    onSelected: (Int) -> Unit,
): BottomSheetDialog {
    val sheet = createAppBottomSheet()
    val inflater = LayoutInflater.from(sheet.context)
    val binding = SheetOptionsBinding.inflate(inflater)
    binding.title.text = title
    ViewCompat.setAccessibilityHeading(binding.title, true)
    options.forEachIndexed { index, label ->
        val layout = if (selectedIndex == null) R.layout.item_sheet_action else R.layout.item_sheet_choice
        val row = inflater.inflate(layout, binding.choices, false) as TextView
        row.id = View.generateViewId()
        row.text = label
        binding.choices.addView(row)
        if (row is RadioButton && index == selectedIndex) binding.choices.check(row.id)
        row.setOnClickListener {
            sheet.dismiss()
            onSelected(index)
        }
    }
    binding.closeButton.isVisible = closeLabel != null
    closeLabel?.let(binding.closeButton::setText)
    binding.closeButton.setOnClickListener { sheet.dismiss() }
    sheet.setAppSheetContent(binding.root)
    return sheet
}
