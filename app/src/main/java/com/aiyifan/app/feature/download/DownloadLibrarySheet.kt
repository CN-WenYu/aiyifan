package com.aiyifan.app.feature.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.ui.showDestructiveConfirmation
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupPageSystemBars
import com.aiyifan.app.databinding.ItemDownloadEpisodeBinding
import com.aiyifan.app.databinding.SheetDownloadSelectionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Reads only the current library snapshot; never resolves remote details or playback sources. */
internal class DownloadLibrarySheet(
    private val activity: AppCompatActivity,
    val mediaKey: String,
    private val play: (String) -> Unit,
    private val enqueue: (VideoDetail, Episode) -> Unit,
    private val remove: (List<String>) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_Aiyifan_DownloadSheet)
    private val binding = SheetDownloadSelectionBinding.inflate(LayoutInflater.from(dialog.context))
    private var managing = false
    private val selected = linkedSetOf<String>()
    private var episodes = emptyList<LibraryEpisode>()
    private val adapter = EpisodeAdapter()
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
    }

    fun show(film: DownloadFilm) {
        dialog.setContentView(binding.root)
        binding.root.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()
        binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
        dialog.behavior.apply {
            maxWidth = activity.dp(640)
            skipCollapsed = true
            setShouldRemoveExpandedCorners(false)
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        binding.videoTitle.text = film.detail.title
        binding.closeButton.contentDescription = activity.getString(R.string.download_library_close)
        binding.closeButton.setOnClickListener { dialog.dismiss() }
        binding.networkHint.isVisible = false
        binding.statusScroll.isVisible = false
        binding.secondaryButton.setText(R.string.download_library_manage)
        binding.secondaryButton.setOnClickListener {
            managing = true
            currentFilm?.let(::update)
        }
        val grid = GridLayoutManager(activity, 4)
        binding.episodes.layoutManager = grid
        binding.episodes.adapter = adapter
        binding.episodes.itemAnimator = null
        binding.episodes.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val columns = ((view.width - view.paddingLeft - view.paddingRight) / activity.dp(86)).coerceIn(2, 6)
            if (grid.spanCount != columns) grid.spanCount = columns
        }
        binding.selectAllButton.setOnClickListener {
            val ids = episodes.mapNotNull { it.downloadId }
            if (selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            renderSelection()
        }
        binding.primaryButton.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            val ids = selected.toList()
            activity.showDestructiveConfirmation(R.string.download_library_delete_title,
                activity.getString(R.string.download_library_delete_confirmation, ids.size), R.string.download_delete) {
                dialog.dismiss()
                remove(ids)
            }
        }
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(lifecycleObserver)
            onDismiss()
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        update(film)
        dialog.show()
        dialog.window?.setupPageSystemBars()
    }

    fun update(film: DownloadFilm?) {
        if (film == null) { dialog.dismiss(); return }
        currentFilm = film
        binding.sheetTitle.setText(if (managing) R.string.download_library_manage_title else R.string.download_library_episodes)
        binding.primaryButton.isVisible = managing
        binding.selectAllButton.isVisible = managing
        binding.secondaryButton.isVisible = !managing
        episodes = if (managing) film.episodes.filter { it.state == LibraryEpisodeState.DOWNLOADED } else film.episodes
        selected.retainAll(episodes.mapNotNull { it.downloadId }.toSet())
        binding.episodeCount.text = activity.getString(R.string.download_episode_count, episodes.size)
        renderSelection()
    }

    private fun renderSelection() {
        binding.selectionSummary.text = when {
            !managing -> activity.getString(R.string.download_library_play_hint)
            selected.isEmpty() -> activity.getString(R.string.download_library_select_hint)
            else -> activity.getString(R.string.download_selection_count, selected.size)
        }
        binding.primaryButton.text = activity.getString(R.string.download_library_delete_count, selected.size)
        binding.primaryButton.isEnabled = selected.isNotEmpty()
        binding.selectAllButton.setText(if (episodes.isNotEmpty() && selected.size == episodes.size) R.string.download_deselect_all else R.string.download_select_all)
        adapter.notifyDataSetChanged()
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount() = episodes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EpisodeHolder(ItemDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val item = episodes[position]
            bindLibraryEpisode(
                holder.binding,
                item,
                managing,
                item.downloadId in selected,
                allowUnavailableClick = !managing,
            ) {
                if (managing) {
                    val id = item.downloadId ?: return@bindLibraryEpisode
                    if (!selected.add(id)) selected.remove(id)
                    renderSelection()
                } else {
                    when (item.state) {
                        LibraryEpisodeState.DOWNLOADED -> item.downloadId?.let {
                            dialog.dismiss()
                            play(it)
                        }
                        LibraryEpisodeState.NOT_DOWNLOADED -> currentFilm?.detail?.let { enqueue(it, item.episode) }
                        else -> Unit
                    }
                }
            }
        }
    }

    private var currentFilm: DownloadFilm? = null

    fun dismiss() = dialog.dismiss()

    private class EpisodeHolder(val binding: ItemDownloadEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
