package com.aiyifan.app.feature.download

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.databinding.ItemDownloadBinding
import com.aiyifan.app.databinding.ItemDownloadEpisodeBinding
import com.aiyifan.app.databinding.ItemDownloadFilmBinding
import com.bumptech.glide.Glide
import java.io.File

internal data class DownloadRow(
    val id: String, val title: String, val subtitle: String, val status: String,
    val size: String, val percent: Int?, val indeterminate: Boolean, val action: Int,
    val enabled: Boolean, val canDelete: Boolean, val poster: File?,
)

internal class DownloadAdapter(private val action: (String) -> Unit, private val more: (String, android.view.View) -> Unit) :
    ListAdapter<DownloadRow, DownloadAdapter.Holder>(object : DiffUtil.ItemCallback<DownloadRow>() {
        override fun areItemsTheSame(oldItem: DownloadRow, newItem: DownloadRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRow, newItem: DownloadRow) = oldItem == newItem
    }) {
    class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            title.text = row.title
            subtitle.text = row.subtitle
            status.text = row.status
            size.text = row.size
            // LinearProgressIndicator requires hiding before switching to indeterminate mode.
            progress.hide()
            progress.isIndeterminate = row.indeterminate
            if (!row.indeterminate) progress.setProgressCompat(row.percent ?: 0, false)
            progress.show()
            actionButton.setText(row.action)
            actionButton.isEnabled = row.enabled
            deleteButton.isEnabled = row.canDelete
            actionButton.setOnClickListener { action(row.id) }
            deleteButton.setOnClickListener { more(row.id, it) }
            poster.loadLocalPoster(row.poster)
        }
    }
}

internal data class DownloadFilmRow(val film: DownloadFilm, val poster: File?)

internal class DownloadFilmAdapter(
    private val play: (String) -> Unit,
    private val episode: (String) -> Unit,
    private val showAll: (String) -> Unit,
    private val manage: (String) -> Unit,
) : ListAdapter<DownloadFilmRow, DownloadFilmAdapter.Holder>(object : DiffUtil.ItemCallback<DownloadFilmRow>() {
    override fun areItemsTheSame(oldItem: DownloadFilmRow, newItem: DownloadFilmRow) = oldItem.film.detail.mediaKey == newItem.film.detail.mediaKey
    override fun areContentsTheSame(oldItem: DownloadFilmRow, newItem: DownloadFilmRow) = oldItem == newItem
}) {
    class Holder(val binding: ItemDownloadFilmBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDownloadFilmBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val film = row.film
        val context = holder.itemView.context
        with(holder.binding) {
            title.text = film.detail.title
            meta.text = listOfNotNull(film.detail.typeName, film.detail.publishTime, film.detail.updateMsg)
                .filter { it.isNotBlank() }.joinToString(" · ")
            meta.isVisible = meta.text.isNotEmpty()
            credits.text = film.detail.actor?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.download_library_credits, it) }
            credits.isVisible = !credits.text.isNullOrEmpty()
            summary.text = context.getString(R.string.download_library_summary, film.completed.size, Formatter.formatShortFileSize(context, film.totalBytes))
            poster.loadLocalPoster(row.poster)
            filmInfo.setOnClickListener { play(film.detail.mediaKey) }
            playButton.setOnClickListener { play(film.detail.mediaKey) }
            allButton.setOnClickListener { showAll(film.detail.mediaKey) }
            manageButton.setOnClickListener { manage(film.detail.mediaKey) }
            previews.removeAllViews()
            film.episodes.take(6).forEach { item ->
                val chip = ItemDownloadEpisodeBinding.inflate(LayoutInflater.from(context), previews, false)
                chip.root.layoutParams = LinearLayout.LayoutParams(context.dp(72), context.dp(44)).apply { marginEnd = context.dp(4) }
                bindLibraryEpisode(chip, item, managing = false, selected = false) { item.downloadId?.let(episode) }
                previews.addView(chip.root)
            }
        }
    }
}

internal fun bindLibraryEpisode(
    binding: ItemDownloadEpisodeBinding,
    episode: LibraryEpisode,
    managing: Boolean,
    selected: Boolean,
    allowUnavailableClick: Boolean = false,
    click: () -> Unit,
) {
    val context = binding.root.context
    val available = episode.state == LibraryEpisodeState.DOWNLOADED
    val clickable = available || allowUnavailableClick && episode.state == LibraryEpisodeState.NOT_DOWNLOADED
    binding.episodeTitle.text = episode.episode.episodeTitle
    binding.episodeState.text = episode.watchPercent?.let { context.getString(R.string.download_library_watched_percent, it) }
        ?: context.getString(episode.state.label())
    binding.episodeState.setTextColor(context.getColor(if (available) R.color.accent else R.color.text_secondary))
    binding.root.isCheckable = managing
    binding.root.isChecked = selected
    binding.root.strokeWidth = context.dp(if (selected) 2 else 1)
    binding.root.setStrokeColor(context.getColor(if (selected) R.color.accent else R.color.outline))
    binding.root.setCardBackgroundColor(context.getColor(if (selected) R.color.chip_selected else R.color.field_bg))
    binding.root.isEnabled = clickable
    binding.root.isClickable = clickable
    binding.root.isFocusable = true
    binding.root.contentDescription = listOfNotNull(
        episode.episode.episodeTitle,
        episode.watchPercent?.let { context.getString(R.string.download_library_watched_percent, it) },
        context.getString(episode.state.label()),
    ).joinToString("，")
    binding.root.setOnClickListener { if (clickable) click() }
}

internal fun LibraryEpisodeState.label(): Int = when (this) {
    LibraryEpisodeState.DOWNLOADED -> R.string.download_library_downloaded
    LibraryEpisodeState.NOT_DOWNLOADED -> R.string.download_library_not_downloaded
    LibraryEpisodeState.DOWNLOADING -> R.string.download_running
    LibraryEpisodeState.WAITING_WIFI -> R.string.download_waiting_wifi
    LibraryEpisodeState.WAITING_NETWORK -> R.string.download_waiting_network
    LibraryEpisodeState.QUEUED -> R.string.download_queued
    LibraryEpisodeState.PAUSED -> R.string.download_paused
    LibraryEpisodeState.FAILED -> R.string.download_failed
    LibraryEpisodeState.REMOVING -> R.string.download_removing
    LibraryEpisodeState.PREPARING -> R.string.download_library_preparing
}

private fun ImageView.loadLocalPoster(file: File?) {
    Glide.with(this).load(file).placeholder(R.drawable.bg_poster).error(R.drawable.bg_poster).into(this)
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
