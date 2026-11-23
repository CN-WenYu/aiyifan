package com.aiyifan.app.feature.anime

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.data.anime.AnimeExploreSubject
import com.aiyifan.app.core.data.anime.AnimePage
import com.aiyifan.app.core.data.anime.isAnimeKey
import com.aiyifan.app.databinding.FragmentAnimeExploreBinding
import com.aiyifan.app.feature.search.SearchActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import kotlinx.coroutines.launch

internal class AnimeExploreFragment : Fragment(R.layout.fragment_anime_explore) {
    private val model by lazy { ViewModelProvider(this)[AnimeExploreViewModel::class.java] }
    private var header: AnimeExploreHeaderAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentAnimeExploreBinding.bind(view)
        val sections = AnimeExploreHeaderAdapter(
            onOpen = ::openAnime,
            onSchedule = { startActivity(AnimeBrowseActivity.intent(requireContext(), schedule = true)) },
            onRanking = { startActivity(AnimeBrowseActivity.intent(requireContext(), schedule = false)) },
            onRetry = model::loadTrending,
        )
        header = sections
        val posters = AnimePosterAdapter(onOpen = ::openAnime)
        val footer = AnimeLoadAdapter(model::loadRecommendations)
        val adapter = ConcatAdapter(sections, posters, footer)
        val grid = GridLayoutManager(requireContext(), 1)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position in sections.itemCount until sections.itemCount + posters.itemCount) 1 else grid.spanCount
        }

        binding.animeSearch.setOnClickListener { startActivity(SearchActivity.animeIntent(requireContext())) }
        binding.animeExploreList.apply {
            layoutManager = grid
            itemAnimator = null
            this.adapter = adapter
            val minWidth = resources.getDimensionPixelSize(R.dimen.anime_grid_poster_min_width)
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val spans = ((right - left - paddingLeft - paddingRight) / minWidth).coerceAtLeast(1)
                if (grid.spanCount != spans) grid.spanCount = spans
            }
            appendOnScroll(
                itemCount = { adapter.itemCount },
                hasMore = { model.recommended.value.canAppend() },
                load = model::loadRecommendations,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.trending.collect(sections::renderTrending) }
                launch {
                    model.recommended.collect { state ->
                        posters.submitList(state.items)
                        footer.show(state.notice(canAppend = true))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val history = AppGraph.catalogRepository.getHistory()
            .filter { it.mediaKey.isAnimeKey() }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.mediaKey }
            .take(10)
        header?.renderHistory(history)
    }

    override fun onDestroyView() {
        view?.let { FragmentAnimeExploreBinding.bind(it).animeExploreList.adapter = null }
        header = null
        super.onDestroyView()
    }

    private fun openAnime(mediaKey: String) {
        startActivity(VideoPlayerActivity.intent(requireContext(), mediaKey))
    }
}

internal class AnimeExploreViewModel : ViewModel() {
    private val repository = AppGraph.animeExplore
    private val trendingLoader = AnimePageLoader(AnimeExploreSubject::mediaKey) {
        AnimePage(repository.trending(), null)
    }
    private val recommendationsLoader = AnimePageLoader(AnimeExploreSubject::mediaKey, repository::recommendations)
    val trending = trendingLoader.state
    val recommended = recommendationsLoader.state

    init {
        loadTrending()
        loadRecommendations()
    }

    fun loadTrending() {
        viewModelScope.launch { trendingLoader.loadNext() }
    }

    fun loadRecommendations() {
        viewModelScope.launch { recommendationsLoader.loadNext() }
    }
}

internal fun AnimePageState<*>.canAppend(): Boolean =
    nextOffset != null && nextOffset > 0 && status == AnimeLoadStatus.IDLE
