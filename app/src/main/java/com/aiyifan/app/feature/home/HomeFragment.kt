package com.aiyifan.app.feature.home

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.children
import androidx.core.view.setMargins
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.aiyifan.app.BuildConfig
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.model.Category
import com.aiyifan.app.core.model.VideoSummary
import com.aiyifan.app.core.ui.ChipAppearanceResolver
import com.aiyifan.app.databinding.FragmentHomeBinding
import com.aiyifan.app.feature.history.HistoryActivity
import com.aiyifan.app.feature.search.SearchActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val repository = AppGraph.catalogRepository
    private lateinit var adapter: HomeVideoAdapter
    private lateinit var bannerAdapter: HomeBannerAdapter
    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var statusAdapter: HomeStatusAdapter
    private lateinit var continueStore: ContinueWatchingStore
    private var removeHistoryListener: (() -> Unit)? = null
    private val pagination = HomeFeedPagination()
    private var selectedCategory: Category? = null
    private var homeRequestVersion = 0L
    private var isInitialPageLoading = false
    private var homeJob: Job? = null
    private var pageJob: Job? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkAvailable: Boolean? = null
    private val connectivityManager: ConnectivityManager
        get() = requireContext().getSystemService(ConnectivityManager::class.java)

    private val initialCategoryId: String?
        get() = arguments?.getString(ARG_INITIAL_CATEGORY_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bannerAdapter = HomeBannerAdapter { video ->
            startActivity(VideoPlayerActivity.intent(requireContext(), video.mediaKey))
        }
        adapter = HomeVideoAdapter { video ->
            startActivity(VideoPlayerActivity.intent(requireContext(), video.mediaKey))
        }
        continueStore = ContinueWatchingStore(requireContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE))
        continueAdapter = ContinueWatchingAdapter(
            onOpen = { startActivity(VideoPlayerActivity.intent(requireContext(), it.mediaKey)) },
            onHide = { continueStore.hide(it); refreshContinueWatching() },
            onAll = { startActivity(Intent(requireContext(), HistoryActivity::class.java)) },
        )
        statusAdapter = HomeStatusAdapter { loadHome(forceRefresh = true) }
        binding.videoRecycler.layoutManager = GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    when {
                        position < bannerAdapter.itemCount -> 2
                        position < bannerAdapter.itemCount + continueAdapter.itemCount -> 2
                        position < bannerAdapter.itemCount + continueAdapter.itemCount + statusAdapter.itemCount -> 2
                        else -> adapter.spanSizeAt(position - bannerAdapter.itemCount - continueAdapter.itemCount - statusAdapter.itemCount)
                    }
            }
        }
        binding.videoRecycler.adapter = ConcatAdapter(bannerAdapter, continueAdapter, statusAdapter, adapter)
        refreshContinueWatching()
        binding.homeRefresh.setColorSchemeResources(R.color.accent)
        binding.homeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface)
        binding.videoRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || recyclerView.canScrollVertically(1) || isInitialPageLoading || networkAvailable != true) return
                val category = selectedCategory ?: return
                val page = pagination.beginNextPage()
                if (page == null) {
                    if (!pagination.hasMore) showToast("没有更多了")
                    return
                }
                val requestVersion = homeRequestVersion
                recyclerView.post {
                    if (isCurrentRequest(requestVersion) && selectedCategory?.id == category.id) {
                        loadNextPage(category, page, requestVersion)
                    }
                }
            }
        })
        binding.searchBox.setOnClickListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        binding.animeButton.isVisible = BuildConfig.ANIME_ENABLED
        binding.animeButton.setOnClickListener { startActivity(SearchActivity.animeIntent(requireContext())) }
        binding.historyButton.setOnClickListener { startActivity(Intent(requireContext(), HistoryActivity::class.java)) }
        binding.navigationButton.setOnClickListener {
            startActivity(Intent(requireContext(), com.aiyifan.app.feature.navigation.NavigationActivity::class.java))
        }
        binding.homeRefresh.setOnRefreshListener { loadHome(forceRefresh = true) }
    }

    override fun onStart() {
        super.onStart()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refreshConnectivity() {
                mainHandler.post {
                    if (connectivityCallback === this && _binding != null) updateConnectivity()
                }
            }
            override fun onAvailable(network: Network) = refreshConnectivity()
            override fun onLost(network: Network) = refreshConnectivity()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshConnectivity()
        }
        connectivityCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
        updateConnectivity()
    }

    override fun onStop() {
        connectivityCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        connectivityCallback = null
        cancelRequests()
        super.onStop()
    }

    private fun isNetworkAvailable(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateConnectivity() {
        val available = isNetworkAvailable()
        val connectivityChanged = networkAvailable != available
        val wasOffline = networkAvailable == false || statusAdapter.status == HomeStatus.OFFLINE
        val needsInitialLoad = statusAdapter.status == HomeStatus.LOADING && !isInitialPageLoading
        networkAvailable = available
        if (!available) {
            if (connectivityChanged || statusAdapter.status != HomeStatus.OFFLINE) showOffline()
        } else if (connectivityChanged || wasOffline || needsInitialLoad) {
            loadHome(forceRefresh = wasOffline)
        }
    }

    private fun cancelRequests() {
        homeRequestVersion++
        homeJob?.cancel()
        pageJob?.cancel()
        homeJob = null
        pageJob = null
        isInitialPageLoading = false
        pagination.cancelPending()
        adapter.setLoadMoreLoading(false)
        _binding?.homeRefresh?.isRefreshing = false
    }

    private fun showOffline() {
        networkAvailable = false
        cancelRequests()
        showStatus(HomeStatus.OFFLINE)
        refreshContinueWatching()
    }

    private fun showStatus(status: HomeStatus) {
        pagination.clear()
        bannerAdapter.submitList(emptyList())
        adapter.submitList(emptyList())
        statusAdapter.submitStatus(status)
    }

    override fun onResume() {
        super.onResume()
        bannerAdapter.setBannerVisible(true)
        refreshContinueWatching()
        removeHistoryListener = repository.addHistoryListener {
            _binding?.videoRecycler?.post { if (_binding != null && isResumed) refreshContinueWatching() }
        }
    }

    override fun onPause() {
        removeHistoryListener?.invoke()
        removeHistoryListener = null
        bannerAdapter.setBannerVisible(false)
        super.onPause()
    }

    private fun loadHome(forceRefresh: Boolean = false) {
        if (!isNetworkAvailable()) {
            showOffline()
            return
        }
        networkAvailable = true
        homeJob?.cancel()
        pageJob?.cancel()
        val requestVersion = ++homeRequestVersion
        isInitialPageLoading = true
        pagination.cancelPending()
        adapter.setLoadMoreLoading(false)
        statusAdapter.submitStatus(HomeStatus.LOADING)
        homeJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val categories = repository.getCategories()
                if (!isCurrentRequest(requestVersion)) return@launch
                val category = categories.firstOrNull { it.id == selectedCategory?.id }
                    ?: categories.firstOrNull { it.id == initialCategoryId }
                    ?: categories.firstOrNull()
                selectedCategory = category
                renderCategories(categories)
                if (category == null) {
                    showStatus(HomeStatus.EMPTY)
                    return@launch
                }
                submitHomeFeed(category, requestVersion, forceRefresh)
            } catch (exception: Throwable) {
                if (exception is CancellationException) throw exception
                if (isCurrentRequest(requestVersion)) {
                    if (!isNetworkAvailable()) showOffline() else showStatus(HomeStatus.ERROR)
                }
            } finally {
                if (isCurrentRequest(requestVersion)) {
                    isInitialPageLoading = false
                    _binding?.homeRefresh?.isRefreshing = false
                }
            }
        }
    }

    private fun refreshContinueWatching() {
        continueAdapter.submitRecords(continueStore.visible(repository.getHistory()))
    }

    private fun submitFeed(videos: List<VideoSummary>) {
        val sections = HomeFeedSections.from(videos)
        bannerAdapter.submitList(sections.banner)
        adapter.submitList(sections.cards)
        statusAdapter.submitStatus(if (videos.isEmpty()) HomeStatus.EMPTY else HomeStatus.CONTENT)
    }

    private fun renderCategories(categories: List<Category>) {
        binding.categoryContainer.removeAllViews()
        categories.forEach { category ->
            val appearance = ChipAppearanceResolver.forSelection(category.id == selectedCategory?.id)
            val tab = TextView(requireContext()).apply {
                text = category.name
                textSize = 14f
                tag = category.id
                setTextColor(resources.getColor(appearance.textColorRes, null))
                setBackgroundResource(appearance.backgroundRes)
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.setMargins(4)
                layoutParams = params
                setOnClickListener { selectCategory(category) }
            }
            binding.categoryContainer.addView(tab)
        }
    }

    private fun selectCategory(category: Category) {
        if (!isNetworkAvailable()) {
            showOffline()
            return
        }
        homeJob?.cancel()
        pageJob?.cancel()
        val requestVersion = ++homeRequestVersion
        selectedCategory = category
        isInitialPageLoading = true
        binding.categoryContainer.children.forEach { tab ->
            val appearance = ChipAppearanceResolver.forSelection(tab.tag == category.id)
            tab.setBackgroundResource(appearance.backgroundRes)
            (tab as TextView).setTextColor(resources.getColor(appearance.textColorRes, null))
        }
        pagination.clear()
        bannerAdapter.submitList(emptyList())
        adapter.submitList(emptyList())
        statusAdapter.submitStatus(HomeStatus.LOADING)
        homeJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                submitHomeFeed(category, requestVersion)
            } catch (exception: Throwable) {
                if (exception is CancellationException) throw exception
                if (isCurrentRequest(requestVersion)) {
                    if (!isNetworkAvailable()) showOffline() else showStatus(HomeStatus.ERROR)
                }
            } finally {
                if (isCurrentRequest(requestVersion)) {
                    isInitialPageLoading = false
                    _binding?.homeRefresh?.isRefreshing = false
                }
            }
        }
    }

    private suspend fun submitHomeFeed(category: Category, requestVersion: Long, forceRefresh: Boolean = false) {
        val response = if (forceRefresh) repository.refreshHomeVideoPage(category)
            else repository.getHomeVideoPage(category, page = 1)
        if (isCurrentRequest(requestVersion) && selectedCategory?.id == category.id) {
            submitFeed(pagination.reset(response))
        }
    }

    private fun loadNextPage(category: Category, page: Int, requestVersion: Long) {
        val categoryId = category.id
        adapter.setLoadMoreLoading(true)
        pageJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = repository.getHomeVideoPage(category, page)
                if (isCurrentRequest(requestVersion) && selectedCategory?.id == categoryId) {
                    submitFeed(pagination.append(page, response))
                }
            } catch (exception: Throwable) {
                if (exception is CancellationException) throw exception
                if (isCurrentRequest(requestVersion) && selectedCategory?.id == categoryId) {
                    if (!isNetworkAvailable()) {
                        showOffline()
                        return@launch
                    }
                    pagination.fail(page)
                    adapter.setLoadMoreLoading(false)
                    showToast("加载更多失败")
                }
            }
        }
    }

    private fun isCurrentRequest(requestVersion: Long): Boolean =
        _binding != null && connectivityCallback != null && networkAvailable == true && requestVersion == homeRequestVersion

    private fun showToast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        cancelRequests()
        binding.videoRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_INITIAL_CATEGORY_ID = "initialCategoryId"

        fun newInstance(categoryId: String?): HomeFragment = HomeFragment().apply {
            arguments = Bundle().apply { putString(ARG_INITIAL_CATEGORY_ID, categoryId) }
        }
    }
}
