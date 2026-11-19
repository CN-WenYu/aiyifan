package com.aiyifan.app.feature.download

import androidx.core.view.isVisible
import com.aiyifan.app.R
import com.aiyifan.app.databinding.DownloadEditActionsBinding

internal fun DownloadEditActionsBinding.renderSelection(editing: Boolean, selected: Int, total: Int) {
    root.isVisible = editing
    selectAll.isEnabled = total > 0
    selectAll.isChecked = total > 0 && selected == total
    selectAll.setText(if (selectAll.isChecked) R.string.download_deselect_all else R.string.download_select_all)
    removeSelected.isEnabled = selected > 0
    removeSelected.text = root.context.getString(R.string.download_remove_count, selected)
}
