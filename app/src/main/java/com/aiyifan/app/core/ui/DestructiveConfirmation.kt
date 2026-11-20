package com.aiyifan.app.core.ui

import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.aiyifan.app.R
import com.aiyifan.app.databinding.DialogDestructiveConfirmationBinding

internal fun AppCompatActivity.showDestructiveConfirmation(
    @StringRes title: Int,
    message: String,
    @StringRes action: Int,
    onConfirm: () -> Unit,
) {
    val dialog = createAppBottomSheet()
    val binding = DialogDestructiveConfirmationBinding.inflate(LayoutInflater.from(dialog.context))
    binding.title.setText(title)
    ViewCompat.setAccessibilityHeading(binding.title, true)
    binding.message.text = message
    binding.confirmButton.setText(action)

    dialog.setAppSheetContent(binding.root)
    binding.cancelButton.setOnClickListener { dialog.dismiss() }
    binding.confirmButton.setOnClickListener {
        dialog.dismiss()
        onConfirm()
    }
    dialog.show()
}
