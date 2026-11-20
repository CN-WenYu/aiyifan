@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.feature.download

import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.isVisible
import com.aiyifan.app.core.ui.createAppBottomSheet
import com.aiyifan.app.core.ui.setAppSheetContent
import com.aiyifan.app.core.ui.setupPageSystemBars
import com.aiyifan.app.databinding.SheetDownloadSelectionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Capture exactly the requested IDs. No metadata probe or network action precedes consent. */
internal fun AppCompatActivity.confirmDownloadNetwork(ids: Set<String>, eligible: (String) -> Boolean = { true }, start: (allowOnce: Boolean) -> Unit) {
    if (ids.isEmpty()) return
    val repository = AppGraph.downloadRepository
    if (!repository.needsMobileConsent(ids)) {
        start(false)
        return
    }
    val dialog = createAppBottomSheet()
    val binding = SheetDownloadSelectionBinding.inflate(dialog.layoutInflater)
    dialog.setAppSheetContent(binding.root)
    bindDownloadConsent(binding, dialog, ids, eligible, start)
    dialog.show()
    dialog.window?.setupPageSystemBars()
}

internal fun AppCompatActivity.bindDownloadConsent(
    binding: SheetDownloadSelectionBinding,
    dialog: BottomSheetDialog,
    ids: Set<String>,
    eligible: (String) -> Boolean = { true },
    start: (allowOnce: Boolean) -> Unit,
) {
    val repository = AppGraph.downloadRepository
    val remaining = ids.map { repository.get(it)?.remainingBytes }
    val known = remaining.filterNotNull()
    val size = Formatter.formatShortFileSize(this, known.sum())
    val message = when (known.size) {
        0 -> getString(R.string.download_data_unknown, ids.size)
        ids.size -> getString(R.string.download_data_known, ids.size, size)
        else -> getString(R.string.download_data_partial, ids.size, size, ids.size - known.size)
    }
    binding.root.layoutParams = binding.root.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
    dialog.behavior.skipCollapsed = true
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    binding.sheetTitle.setText(R.string.download_data_title)
    binding.closeButton.contentDescription = getString(R.string.download_consent_close)
    binding.closeButton.setOnClickListener { dialog.dismiss() }
    binding.videoTitle.isVisible = false
    binding.selectionToolbar.isVisible = false
    binding.episodes.isVisible = false
    binding.statusScroll.isVisible = false
    binding.networkHint.isVisible = false
    binding.selectionSummary.isVisible = true
    binding.selectionSummary.text = message
    binding.primaryButton.isEnabled = true
    binding.primaryButton.setText(R.string.download_data_once)
    binding.primaryButton.setOnClickListener { dialog.dismiss(); start(true) }
    // The selection and result pages share these views. Match their geometry only for consent.
    for (button in listOf(binding.secondaryButton, binding.wifiOnlyButton)) {
        button.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = binding.primaryButton.layoutParams.height
            topMargin = resources.getDimensionPixelSize(R.dimen.dp_6)
        }
        button.insetTop = binding.primaryButton.insetTop
        button.insetBottom = binding.primaryButton.insetBottom
        button.cornerRadius = binding.primaryButton.cornerRadius
    }
    binding.wifiOnlyButton.strokeWidth = resources.getDimensionPixelSize(R.dimen.dp_1)
    binding.wifiOnlyButton.strokeColor = getColorStateList(R.color.outline)
    binding.secondaryButton.isVisible = true
    binding.secondaryButton.setText(R.string.download_data_always)
    binding.secondaryButton.setTextColor(getColor(R.color.text_primary))
    binding.secondaryButton.backgroundTintList = getColorStateList(R.color.field_bg)
    binding.secondaryButton.setOnClickListener {
        dialog.dismiss()
        repository.setMobileDataAllowed(true)
        start(false)
    }
    binding.wifiOnlyButton.isVisible = true
    binding.wifiOnlyButton.setOnClickListener {
        dialog.dismiss()
        repository.clearOnce(ids.filter(eligible))
        start(false)
    }
}
