package com.aiyifan.app.feature.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.usesLightSystemBarIcons
import com.aiyifan.app.databinding.SheetDownloadSelectionBinding
import com.aiyifan.app.databinding.ItemDownloadEpisodeBinding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object DownloadSelectionDialog {
    fun show(activity: AppCompatActivity, detail: VideoDetail): BottomSheetDialog? {
        if (detail.episodes.isEmpty()) return null
        return SelectionSheet(activity, detail).show()
    }

    private enum class Stage { SELECT, PREPARING, RESULT, MOBILE_CONFIRM }

    private class SelectionSheet(private val activity: AppCompatActivity, private val detail: VideoDetail) {
        val repository = AppGraph.downloadRepository
        private val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_Aiyifan_DownloadSheet)
        private val binding = SheetDownloadSelectionBinding.inflate(LayoutInflater.from(dialog.context))
        private val selected = linkedSetOf<String>()
        private var existing = emptyMap<String, Int>()
        private var loaded = false
        private var stage = Stage.SELECT
        private var waitingForWifi = false
        private var preparation: Job? = null
        private var observation: Job? = null
        private val adapter = EpisodeGridAdapter(detail.episodes, selected) { key ->
            if (!selected.add(key)) selected.remove(key)
            renderSelection()
        }
        private val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun show(): BottomSheetDialog {
            dialog.setContentView(binding.root)
            val density = activity.resources.displayMetrics.density
            val windowHeight = activity.window.decorView.height.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.heightPixels
            val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            binding.root.layoutParams.height = (windowHeight * if (landscape) 0.9f else 0.72f).toInt()
            dialog.behavior.apply {
                maxWidth = (640 * density).toInt()
                skipCollapsed = true
                setShouldRemoveExpandedCorners(false)
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
            binding.videoTitle.text = detail.title
            binding.episodeCount.text = activity.getString(R.string.download_episode_count, detail.episodes.size)
            val grid = GridLayoutManager(activity, 4)
            binding.episodes.layoutManager = grid
            binding.episodes.adapter = adapter
            binding.episodes.itemAnimator = null
            binding.episodes.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val columns = ((view.width - view.paddingLeft - view.paddingRight) / (82 * density)).toInt().coerceIn(2, 6)
                if (grid.spanCount != columns) grid.spanCount = columns
            }
            binding.closeButton.setOnClickListener { dialog.dismiss() }
            binding.selectAllButton.setOnClickListener {
                val available = detail.episodes.map { it.episodeKey }.filter { it !in existing }
                if (selected.containsAll(available)) selected.clear() else selected.addAll(available)
                renderSelection()
            }
            binding.primaryButton.setOnClickListener {
                when (stage) {
                    Stage.SELECT -> if (selected.isNotEmpty()) prepare()
                    Stage.PREPARING -> preparation?.cancel()
                    Stage.RESULT -> {
                        dialog.dismiss()
                        if (!waitingForWifi) activity.startActivity(Intent(activity, DownloadsActivity::class.java))
                    }
                    Stage.MOBILE_CONFIRM -> {
                        repository.setMobileDataAllowed(true)
                        dialog.dismiss()
                        activity.startActivity(Intent(activity, DownloadsActivity::class.java))
                    }
                }
            }
            binding.secondaryButton.setOnClickListener {
                if (stage == Stage.RESULT && waitingForWifi) {
                    stage = Stage.MOBILE_CONFIRM
                    binding.sheetTitle.setText(R.string.download_mobile_allowed)
                    binding.statusTitle.setText(R.string.download_mobile_confirmation)
                    binding.statusMessage.isVisible = false
                    binding.primaryButton.setText(R.string.download_mobile_enable)
                    binding.secondaryButton.setText(android.R.string.cancel)
                } else dialog.dismiss()
            }
            dialog.setOnDismissListener {
                observation?.cancel()
                preparation?.cancel()
                activity.lifecycle.removeObserver(observer)
            }
            activity.lifecycle.addObserver(observer)
            renderSelection()
            dialog.show()
            dialog.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
                    usesLightSystemBarIcons(activity.resources.configuration.uiMode)
            }
            observation = activity.lifecycleScope.launch {
                try {
                    repository.awaitLoaded()
                    loaded = true
                    repository.tasks.collect { tasks ->
                        existing = tasks.filter { it.metadata?.detail?.mediaKey == detail.mediaKey }
                            .associate { task -> task.metadata!!.episode.episodeKey to
                                if (task.download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
                                    R.string.download_completed else R.string.download_episode_added }
                        selected.removeAll(existing.keys)
                        if (stage == Stage.SELECT) renderSelection()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    binding.selectionSummary.setText(R.string.download_operation_failed)
                }
            }
            return dialog
        }

        private fun renderSelection() {
            binding.selectionSummary.text = when {
                !loaded -> activity.getString(R.string.download_sheet_loading)
                selected.isEmpty() -> activity.getString(R.string.download_selection_empty)
                else -> activity.getString(R.string.download_selection_count, selected.size)
            }
            binding.networkHint.setText(if (repository.mobileDataAllowed) R.string.download_sheet_mobile_hint else R.string.download_sheet_wifi_hint)
            binding.primaryButton.isEnabled = loaded && selected.isNotEmpty()
            binding.primaryButton.text = if (selected.isEmpty()) activity.getString(R.string.download_start)
                else activity.getString(R.string.download_start_count, selected.size)
            val availableCount = detail.episodes.count { it.episodeKey !in existing }
            binding.selectAllButton.isEnabled = loaded && availableCount > 0
            binding.selectAllButton.setText(if (selected.size == availableCount && availableCount > 0)
                R.string.download_deselect_all else R.string.download_select_all)
            adapter.existing = existing
            adapter.loaded = loaded
            adapter.notifyDataSetChanged()
        }

        private fun prepare() {
            val keys = detail.episodes.map { it.episodeKey }.filter { it in selected }
            val episodes = detail.episodes.associateBy { it.episodeKey }
            val reports = mutableListOf<DownloadPreparation>()
            stage = Stage.PREPARING
            binding.episodes.isVisible = false
            binding.selectionToolbar.isVisible = false
            binding.statusScroll.isVisible = true
            binding.statusTitle.text = activity.getString(R.string.download_preparing, 0, keys.size)
            binding.statusMessage.setText(R.string.download_preparation_hint)
            binding.primaryButton.setText(R.string.download_cancel_preparation)
            binding.primaryButton.isEnabled = false
            requestNotificationOnce(activity)
            preparation = activity.lifecycleScope.launch {
                try {
                    // Only local persistence: leaving the sheet does not cancel accepted selections.
                    withContext(NonCancellable) {
                        DownloadBatch.prepare(keys, { it in existing }, { key -> repository.enqueue(detail, episodes.getValue(key)) }, { report ->
                            reports += report
                            binding.statusTitle.text = activity.getString(R.string.download_preparing, reports.size, keys.size)
                        })
                    }
                } finally {
                    if (dialog.isShowing && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        stage = Stage.RESULT
                        waitingForWifi = !repository.mobileDataAllowed && repository.isWaitingForNetwork && reports.any { it.status != PreparationStatus.FAILED }
                        binding.preparationProgress.isVisible = false
                        binding.sheetTitle.setText(R.string.download_sheet_result)
                        binding.statusTitle.text = if (waitingForWifi) activity.getString(R.string.download_added_waiting_wifi,
                            reports.count { it.status != PreparationStatus.FAILED }) else activity.getString(R.string.download_preparation_result,
                            reports.count { it.status == PreparationStatus.ADDED },
                            reports.count { it.status == PreparationStatus.EXISTING },
                            reports.count { it.status == PreparationStatus.FAILED })
                        binding.statusMessage.text = reports.filter { it.status == PreparationStatus.FAILED }.joinToString("\n\n") {
                            episodes[it.id]?.episodeTitle.orEmpty() + "：" + preparationError(activity, it.error)
                        }
                        binding.statusMessage.isVisible = binding.statusMessage.text.isNotEmpty()
                        binding.selectionSummary.text = if (reports.size < keys.size)
                            activity.getString(R.string.download_sheet_not_prepared, keys.size - reports.size)
                            else activity.getString(R.string.download_sheet_result_hint)
                        binding.selectionSummary.isVisible = reports.size < keys.size
                        binding.primaryButton.isEnabled = true
                        binding.primaryButton.setText(if (waitingForWifi) R.string.download_sheet_acknowledge else R.string.download_view_tasks)
                        binding.secondaryButton.setText(if (waitingForWifi) R.string.download_sheet_allow_mobile else R.string.download_sheet_acknowledge)
                        binding.secondaryButton.isVisible = true
                        val windowHeight = activity.window.decorView.height.takeIf { it > 0 }
                            ?: activity.resources.displayMetrics.heightPixels
                        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        binding.root.layoutParams = binding.root.layoutParams.apply {
                            height = (windowHeight * if (landscape) 0.9f else 0.48f).toInt()
                        }
                    }
                }
            }
        }
    }

    private class EpisodeGridAdapter(
        private val episodes: List<Episode>,
        private val selected: Set<String>,
        private val toggle: (String) -> Unit,
    ) : RecyclerView.Adapter<EpisodeGridAdapter.Holder>() {
        var existing: Map<String, Int> = emptyMap()
        var loaded = false
        class Holder(val binding: ItemDownloadEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = episodes.size
        override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
            val episode = episodes[position]
            val checked = episode.episodeKey in selected
            val status = existing[episode.episodeKey]
                ?: if (checked) R.string.download_episode_selected else R.string.download_episode_available
            val context = root.context
            root.isChecked = checked
            root.isEnabled = loaded && episode.episodeKey !in existing
            root.setCardBackgroundColor(ContextCompat.getColor(context, if (checked) R.color.chip_selected else R.color.field_bg))
            root.strokeColor = ContextCompat.getColor(context, if (checked) R.color.accent else R.color.outline)
            root.contentDescription = "${episode.episodeTitle}，${context.getString(status)}"
            episodeTitle.text = episode.episodeTitle
            episodeTitle.setTextColor(ContextCompat.getColor(context, when {
                checked -> R.color.accent
                episode.episodeKey in existing -> R.color.text_secondary
                else -> R.color.text_primary
            }))
            episodeState.setText(status)
            episodeState.setTextColor(ContextCompat.getColor(context, if (checked) R.color.accent else R.color.text_secondary))
            root.setOnClickListener { toggle(episode.episodeKey) }
        }
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
