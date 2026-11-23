package com.aiyifan.app.feature.anime

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.core.data.anime.AnimeExploreSubject
import com.aiyifan.app.core.model.WatchHistory
import com.aiyifan.app.databinding.ItemAnimeExploreHeaderBinding
import com.aiyifan.app.databinding.ItemAnimeLoadStateBinding
import com.aiyifan.app.databinding.ItemAnimePosterBinding
import com.aiyifan.app.databinding.ItemContinueCardBinding
import com.aiyifan.app.feature.history.WatchProgressPresentation
import com.bumptech.glide.Glide

internal class AnimePosterAdapter(
    private val horizontal: Boolean = false,
    private val onOpen: (String) -> Unit,
) : ListAdapter<AnimeExploreSubject, AnimePosterAdapter.Holder>(byKey(AnimeExploreSubject::mediaKey) { old, new -> old == new }) {
    class Holder(val binding: ItemAnimePosterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAnimePosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (horizontal) binding.root.layoutParams.width =
            parent.resources.getDimensionPixelSize(R.dimen.anime_trending_poster_width)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val subject = getItem(position)
        with(holder.binding) {
            animePosterTitle.text = subject.title
            animePosterMeta.text = subject.metadata
            animePosterMeta.isVisible = !subject.metadata.isNullOrBlank()
            animePoster.loadAnimePoster(subject.coverUrl)
            root.setOnClickListener { onOpen(subject.mediaKey) }
        }
    }
}

internal class AnimeExploreHeaderAdapter(
    onOpen: (String) -> Unit,
    private val onSchedule: () -> Unit,
    private val onRanking: () -> Unit,
    private val onRetry: () -> Unit,
) : RecyclerView.Adapter<AnimeExploreHeaderAdapter.Holder>() {
    private val trending = AnimePosterAdapter(horizontal = true, onOpen = onOpen)
    private val history = AnimeHistoryAdapter(onOpen)
    private var trendingState = AnimePageState<AnimeExploreSubject>()
    private var hasHistory = false

    class Holder(val binding: ItemAnimeExploreHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    fun renderTrending(state: AnimePageState<AnimeExploreSubject>) {
        if (trendingState == state) return
        trendingState = state
        trending.submitList(state.items)
        notifyItemChanged(0)
    }

    fun renderHistory(items: List<WatchHistory>) {
        history.submitList(items)
        if (hasHistory == items.isNotEmpty()) return
        hasHistory = items.isNotEmpty()
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAnimeExploreHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.animeTrendingList.setupHorizontal(trending)
        binding.animeContinueList.setupHorizontal(history)
        binding.animeSchedule.setOnClickListener { onSchedule() }
        binding.animeRanking.setOnClickListener { onRanking() }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        with(holder.binding) {
            animeTrendingList.isVisible = trendingState.items.isNotEmpty()
            val message = trendingState.notice()
            animeTrendingStatus.root.isVisible = message != null
            if (message != null) animeTrendingStatus.root.renderNotice(message, onRetry)
            animeContinueList.isVisible = hasHistory
            animeHistoryEmpty.isVisible = !hasHistory
        }
    }

    private fun RecyclerView.setupHorizontal(items: RecyclerView.Adapter<*>) {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        adapter = items
        itemAnimator = null
    }
}

private class AnimeHistoryAdapter(
    private val onOpen: (String) -> Unit,
) : ListAdapter<WatchHistory, AnimeHistoryAdapter.Holder>(byKey(WatchHistory::mediaKey) { old, new -> old == new }) {
    class Holder(val binding: ItemContinueCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemContinueCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = getItem(position)
        with(holder.binding) {
            clearButton.isVisible = false
            title.text = record.title
            val playbackTime = if (WatchProgressPresentation.completed(record.progressMs, record.durationMs)) {
                root.context.getString(R.string.episode_completed)
            } else {
                WatchProgressPresentation.compactTime(record.progressMs, record.durationMs)
            }
            time.text = listOfNotNull(record.episodeTitle, playbackTime).joinToString(" · ")
            progress.progress = WatchProgressPresentation.percent(record.progressMs, record.durationMs)
            poster.loadAnimePoster(record.coverUrl)
            root.setOnClickListener { onOpen(record.mediaKey) }
        }
    }
}

internal class AnimeLoadAdapter(private val onAction: () -> Unit) : RecyclerView.Adapter<AnimeLoadAdapter.Holder>() {
    class Holder(val binding: ItemAnimeLoadStateBinding) : RecyclerView.ViewHolder(binding.root)

    private var notice: AnimeNotice? = null

    fun show(message: AnimeNotice?) {
        if (notice == message) return
        val previous = notice
        notice = message
        when {
            previous == null -> notifyItemInserted(0)
            message == null -> notifyItemRemoved(0)
            else -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (notice == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAnimeLoadStateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        notice?.let { holder.binding.root.renderNotice(it, onAction) }
    }
}

internal data class AnimeNotice(@param:StringRes val text: Int, val actionable: Boolean = false)

internal fun AnimePageState<*>.notice(
    @StringRes empty: Int = R.string.home_status_empty,
    canAppend: Boolean = false,
): AnimeNotice? = when {
    status == AnimeLoadStatus.FAILED -> AnimeNotice(R.string.anime_explore_load_failed, actionable = true)
    status == AnimeLoadStatus.LOADING || (items.isEmpty() && nextOffset == 0) ->
        AnimeNotice(R.string.home_status_loading)
    canAppend && nextOffset != null -> AnimeNotice(R.string.navigation_more, actionable = true)
    items.isEmpty() -> AnimeNotice(empty)
    else -> null
}

internal fun TextView.renderNotice(notice: AnimeNotice, onAction: () -> Unit) {
    setText(notice.text)
    setTextColor(context.getColor(if (notice.actionable) R.color.accent else R.color.text_secondary))
    setOnClickListener(if (notice.actionable) { _ -> onAction() } else null)
    isClickable = notice.actionable
    isFocusable = notice.actionable
}

internal fun ImageView.loadAnimePoster(url: String?) {
    val glide = Glide.with(this)
    if (url.isNullOrBlank()) {
        glide.clear(this)
        setImageResource(R.color.poster_placeholder)
        return
    }
    glide.load(url)
        .placeholder(R.color.poster_placeholder)
        .error(R.color.poster_placeholder)
        .into(this)
}

internal fun <T : Any> byKey(
    key: (T) -> String,
    sameContent: (T, T) -> Boolean,
): DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = key(oldItem) == key(newItem)
    override fun areContentsTheSame(oldItem: T, newItem: T) = sameContent(oldItem, newItem)
}

internal fun RecyclerView.appendOnScroll(itemCount: () -> Int, hasMore: () -> Boolean, load: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
            val layout = recycler.layoutManager as? LinearLayoutManager ?: return
            if (dy <= 0 || !hasMore() || layout.findLastVisibleItemPosition() < itemCount() - 6) return
            recycler.post { if (recycler.isAttachedToWindow && hasMore()) load() }
        }
    })
}
