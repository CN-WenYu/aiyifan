package com.aiyifan.app.feature.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.model.WatchHistory
import com.aiyifan.app.core.ui.createAppBottomSheet
import com.aiyifan.app.core.ui.setAppSheetContent
import com.aiyifan.app.core.ui.setupPageSystemBars
import com.aiyifan.app.core.ui.showDestructiveConfirmation
import com.aiyifan.app.databinding.ItemDownloadEpisodeBinding
import com.aiyifan.app.databinding.SheetDownloadSelectionBinding

/** Reads only the current library snapshot; never resolves remote details or playback sources. */
internal class DownloadLibrarySheet(
    private val activity: AppCompatActivity,
    val mediaKey: String,
    private val play: (String) -> Unit,
    private val enqueue: (VideoDetail, Episode) -> Unit,
    private val remove: (List<String>) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dialog = activity.createAppBottomSheet()
    private val binding = SheetDownloadSelectionBinding.inflate(LayoutInflater.from(dialog.context))
    private var managing = false
    private val selected = linkedSetOf<String>()
    private lateinit var film: DownloadFilm
    private var removing = emptySet<String>()
    private val adapter = EpisodeAdapter()
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
    }

    fun show(film: DownloadFilm) {
        this.film = film
        dialog.setAppSheetContent(binding.root)
        binding.root.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()

        binding.videoTitle.text = film.detail.title
        binding.closeButton.contentDescription = activity.getString(R.string.download_library_close)
        binding.closeButton.setOnClickListener { dialog.dismiss() }
        binding.networkHint.isVisible = false
        binding.statusScroll.isVisible = false
        binding.selectionSummary.minLines = 2
        binding.primaryButton.backgroundTintList = activity.getColorStateList(R.color.download_delete_background)
        binding.primaryButton.setTextColor(activity.getColorStateList(R.color.download_delete_label))
        binding.manageDoneButton.setOnClickListener { setManaging(false) }

        val grid = GridLayoutManager(activity, 4)
        binding.episodes.layoutManager = grid
        binding.episodes.adapter = adapter
        binding.episodes.itemAnimator = null
        binding.episodes.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val columns = ((view.width - view.paddingLeft - view.paddingRight) / activity.dp(86)).coerceIn(2, 6)
            if (grid.spanCount != columns) grid.spanCount = columns
        }
        binding.selectAllButton.setOnClickListener {
            val ids = selectableIds()
            if (ids.isEmpty()) return@setOnClickListener
            if (selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            renderSelection()
        }
        binding.primaryButton.setOnClickListener {
            if (!managing) {
                if (selectableIds().isNotEmpty()) setManaging(true)
                return@setOnClickListener
            }
            val ids = selected.intersect(selectableIds()).toList()
            if (ids.isEmpty()) return@setOnClickListener
            activity.showDestructiveConfirmation(
                R.string.download_library_delete_title,
                activity.getString(R.string.download_library_delete_confirmation, ids.size),
                R.string.download_delete,
            ) {
                remove(ids)
            }
        }
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(lifecycleObserver)
            onDismiss()
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        renderSelection()
        dialog.show()
        dialog.window?.setupPageSystemBars()
    }

    fun update(
        entries: List<LibraryDownload>,
        snapshot: VideoDetail?,
        history: (String, String) -> WatchHistory?,
        removing: Set<String>,
    ) {
        film = downloadFilm(entries, snapshot, retained = film, history = history)
        this.removing = removing.toSet()
        renderSelection()
    }

    private fun selectableIds(): Set<String> = film.completed.mapNotNullTo(linkedSetOf()) {
        it.id.takeUnless(removing::contains)
    }

    private fun setManaging(enabled: Boolean, preselected: String? = null) {
        managing = enabled
        selected.clear()
        preselected?.let(selected::add)
        renderSelection()
    }

    private fun renderSelection() {
        val selectable = selectableIds()
        selected.retainAll(selectable)
        binding.sheetTitle.setText(if (managing) R.string.download_library_manage_title else R.string.download_library_episodes)
        binding.manageDoneButton.isVisible = managing
        binding.selectAllButton.isVisible = managing
        binding.selectAllButton.isEnabled = selectable.isNotEmpty()
        binding.selectAllButton.setText(
            if (selectable.isNotEmpty() && selected.size == selectable.size) R.string.download_deselect_all else R.string.download_select_all,
        )
        binding.episodeCount.text = activity.getString(R.string.download_episode_count, film.episodes.size)
        binding.selectionSummary.text = when {
            !managing -> activity.getString(R.string.download_library_play_hint)
            selected.isNotEmpty() -> activity.getString(R.string.download_selection_count, selected.size)
            selectable.isEmpty() && film.episodes.none { it.downloadId in removing } ->
                activity.getString(R.string.download_library_no_downloaded_episodes)
            else -> activity.getString(R.string.download_library_select_hint)
        }
        binding.primaryButton.text = if (managing) {
            activity.getString(R.string.download_library_delete_count, selected.size)
        } else {
            activity.getString(R.string.download_library_manage)
        }
        binding.primaryButton.isEnabled = if (managing) selected.isNotEmpty() else selectable.isNotEmpty()
        adapter.submitList(film.episodes.map { episode ->
            EpisodeRow(episode, managing, episode.downloadId in selected, episode.downloadId in removing)
        })
    }

    private fun clickEpisode(key: String) {
        val episode = film.episodes.firstOrNull { it.episode.episodeKey == key } ?: return
        val id = episode.downloadId
        if (id in removing) return
        if (managing) {
            if (id == null || episode.state != LibraryEpisodeState.DOWNLOADED) return
            if (!selected.add(id)) selected.remove(id)
            renderSelection()
        } else {
            when (episode.state) {
                LibraryEpisodeState.DOWNLOADED -> id?.let {
                    dialog.dismiss()
                    play(it)
                }
                LibraryEpisodeState.NOT_DOWNLOADED -> enqueue(film.detail, episode.episode)
                else -> Unit
            }
        }
    }

    private fun manageEpisode(key: String): Boolean {
        if (managing) return false
        val id = film.episodes.firstOrNull { it.episode.episodeKey == key }?.downloadId ?: return false
        if (id !in selectableIds()) return false
        setManaging(true, preselected = id)
        return true
    }

    fun dismiss() = dialog.dismiss()

    private data class EpisodeRow(
        val episode: LibraryEpisode,
        val managing: Boolean,
        val selected: Boolean,
        val removing: Boolean,
    )

    private inner class EpisodeAdapter : ListAdapter<EpisodeRow, EpisodeHolder>(object : DiffUtil.ItemCallback<EpisodeRow>() {
        override fun areItemsTheSame(oldItem: EpisodeRow, newItem: EpisodeRow) =
            oldItem.episode.episode.episodeKey == newItem.episode.episode.episodeKey

        override fun areContentsTheSame(oldItem: EpisodeRow, newItem: EpisodeRow) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EpisodeHolder(ItemDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val row = getItem(position)
            val key = row.episode.episode.episodeKey
            bindLibraryEpisode(
                holder.binding, row.episode, row.managing, row.selected, row.removing,
                click = { clickEpisode(key) },
                longPress = { manageEpisode(key) },
            )
        }
    }

    private class EpisodeHolder(val binding: ItemDownloadEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
