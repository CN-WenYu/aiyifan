package com.aiyifan.app.feature.download

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioButton
import androidx.core.view.ViewCompat
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.databinding.DialogDownloadCountBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal fun Context.showDownloadCountDialog(onSelected: () -> Unit) {
    val repository = AppGraph.downloadRepository
    val builder = MaterialAlertDialogBuilder(this).setBackground(getDrawable(R.drawable.bg_confirmation_panel))
    val inflater = LayoutInflater.from(builder.context)
    val binding = DialogDownloadCountBinding.inflate(inflater)
    ViewCompat.setAccessibilityHeading(binding.title, true)
    val dialog = builder.setView(binding.root).create()
    for (count in 1..3) {
        val choice = inflater.inflate(R.layout.item_download_count, binding.choices, false) as RadioButton
        choice.text = getString(R.string.download_parallel_option, count)
        binding.choices.addView(choice)
        choice.isChecked = count == repository.parallelDownloads
        choice.setOnClickListener {
            repository.setParallelDownloads(count)
            dialog.dismiss()
            onSelected()
        }
    }
    dialog.show()
}
