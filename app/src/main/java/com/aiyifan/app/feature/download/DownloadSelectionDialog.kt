package com.aiyifan.app.feature.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.VideoDetail
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object DownloadSelectionDialog {
    fun show(activity: AppCompatActivity, detail: VideoDetail) {
        if (detail.episodes.isEmpty()) return
        val repository = AppGraph.downloadRepository
        val checked = BooleanArray(detail.episodes.size)
        val existing = repository.tasks.value.mapNotNull { task ->
            task.metadata?.takeIf { it.detail.mediaKey == detail.mediaKey }?.episode?.episodeKey
        }.toSet()
        val labels = detail.episodes.map { episode ->
            if (episode.episodeKey in existing) activity.getString(R.string.download_existing_episode, episode.episodeTitle)
            else episode.episodeTitle
        }.toTypedArray()
        val picker = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.download_select_episodes)
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.download_select_all, null)
            .setPositiveButton(R.string.download_start, null)
            .create()
        picker.setOnShowListener {
            picker.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val select = checked.any { !it }
                checked.indices.forEach { checked[it] = select; picker.listView.setItemChecked(it, select) }
            }
            picker.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val episodes = detail.episodes.filterIndexed { index, _ -> checked[index] }
                if (episodes.isEmpty()) return@setOnClickListener
                picker.dismiss()
                requestNotificationOnce(activity)
                prepare(activity, detail, episodes.map { it.episodeKey })
            }
        }
        picker.show()
    }

    private fun prepare(activity: AppCompatActivity, detail: VideoDetail, keys: List<String>) {
        val repository = AppGraph.downloadRepository
        val episodes = detail.episodes.associateBy { it.episodeKey }
        var job: Job? = null
        val reports = mutableListOf<DownloadPreparation>()
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.download_preparing, 0, keys.size))
            .setMessage(R.string.download_preparation_hint)
            .setNegativeButton(R.string.download_cancel_preparation) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { job?.cancel(); progress.dismiss() }
        }
        activity.lifecycle.addObserver(observer)
        progress.show()
        job = activity.lifecycleScope.launch {
            try {
                repository.awaitLoaded()
                DownloadBatch.prepare(keys, { key ->
                    repository.tasks.value.any { it.metadata?.let { meta ->
                        meta.detail.mediaKey == detail.mediaKey && meta.episode.episodeKey == key
                    } == true }
                }, { key -> repository.enqueue(detail, episodes.getValue(key)) }, { report ->
                    reports += report
                    progress.setTitle(activity.getString(R.string.download_preparing, reports.size, keys.size))
                })
            } finally {
                activity.lifecycle.removeObserver(observer)
                progress.dismiss()
                // The result is only presented while this activity is still visible.
                if (activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    showResult(activity, reports, episodes.mapValues { it.value.episodeTitle })
                }
            }
        }
    }

    private fun showResult(activity: AppCompatActivity, reports: List<DownloadPreparation>, titles: Map<String, String>) {
        val failed = reports.filter { it.status == PreparationStatus.FAILED }
        val summary = activity.getString(R.string.download_preparation_result,
            reports.count { it.status == PreparationStatus.ADDED }, reports.count { it.status == PreparationStatus.EXISTING }, failed.size)
        val message = if (failed.isEmpty()) summary else summary + "\n\n" + activity.getString(
            R.string.download_preparation_failed, failed.joinToString("\n") {
                titles[it.id].orEmpty() + "：" + preparationError(activity, it.error)
            })
        MaterialAlertDialogBuilder(activity).setMessage(message)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.download_view_tasks) { _, _ ->
                activity.startActivity(Intent(activity, DownloadsActivity::class.java))
            }.show()
    }

    private fun preparationError(activity: AppCompatActivity, error: Exception?): String {
        // Only display our own fixed messages, never a remote exception that might contain a signed URL.
        val safeMessages = listOf(R.string.download_error_source, R.string.download_error_format,
            R.string.download_error_live, R.string.download_error_drm, R.string.download_error_tracks,
            R.string.download_error_prepare).map(activity::getString)
        return error?.message?.takeIf { it in safeMessages }
            ?: if (error is java.io.IOException) activity.getString(com.aiyifan.app.core.download.DownloadFailure.from(error).messageRes)
            else activity.getString(R.string.download_error_prepare)
    }

    private fun requestNotificationOnce(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = activity.getSharedPreferences("download_ui", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("notification_requested", false)) {
            preferences.edit().putBoolean("notification_requested", true).apply()
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7303)
        } else {
            Toast.makeText(activity, R.string.download_notification_permission_hint, Toast.LENGTH_SHORT).show()
        }
    }
}
