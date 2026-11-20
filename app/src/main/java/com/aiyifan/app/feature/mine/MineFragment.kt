package com.aiyifan.app.feature.mine

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.ui.ThemeMode
import com.aiyifan.app.core.ui.ThemePreferenceStore
import com.aiyifan.app.core.ui.createOptionsSheet
import com.aiyifan.app.databinding.FragmentMineBinding
import com.aiyifan.app.feature.download.showDownloadCountDialog
import com.aiyifan.app.feature.collection.CollectionActivity
import com.aiyifan.app.feature.history.HistoryActivity
import com.aiyifan.app.feature.hot.HotActivity
import com.bumptech.glide.Glide

class MineFragment : Fragment() {
    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private lateinit var slideshow: HeaderSlideshow
    private lateinit var availability: HeaderImageAvailability
    private val images = HeaderImageSource.allNames
    private val localIndices = images.indices.filter { HeaderImageSource.isLocal(images[it]) }
    private val connectivity get() = requireContext().getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasResumed = false
    private var viewerLaunched = false
    private var carousel: HeaderCarousel? = null
    private var headerExpanded = false
    private var topInset = 0
    private var headerWasBehindStatusBar = true
    private val headerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateHeaderStatusBar()
    }
    private var windowRoot: View? = null
    private val windowLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            configureExpansion(forceReload = right - left != oldRight - oldLeft)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headerExpanded = savedInstanceState?.getBoolean("header_expanded") ?: false
        hasResumed = savedInstanceState?.getBoolean("header_retry_on_resume") ?: false
        viewerLaunched = savedInstanceState?.getBoolean("header_viewer_launched") ?: false
        availability = HeaderImageAvailability(
            online = connectivity.hasInternetNetwork(),
            retryAt = savedInstanceState?.getLong("header_retry_at", -1L)?.takeIf { it >= 0 },
        )
        slideshow = HeaderSlideshow(
            count = images.size,
            initialPaused = savedInstanceState?.getBoolean("header_paused") ?: false,
            initialPool = if (availability.useRemote) images.indices.toList() else localIndices,
            restored = savedInstanceState?.getBundle("header_order")?.restoreHeaderOrder(),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("header_order", slideshow.snapshot().toBundle())
        availability.retryAt?.let { outState.putLong("header_retry_at", it) }
        outState.putBoolean("header_paused", slideshow.paused)
        outState.putBoolean("header_expanded", headerExpanded)
        outState.putBoolean("header_retry_on_resume", hasResumed && isHidden)
        outState.putBoolean("header_viewer_launched", viewerLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        windowRoot = requireActivity().window.decorView
        binding.root.setOnScrollChangeListener { _, _, _, _, _ -> updateHeaderStatusBar() }
        binding.mineHeader.addOnLayoutChangeListener(headerLayoutListener)
        configureExpansion(reload = false)
        binding.mineHeader.restoreExpanded(headerExpanded)
        headerExpanded = binding.mineHeader.expanded
        binding.mineHeader.isPageAtTop = { _binding?.root?.scrollY == 0 }
        binding.mineHeader.scrollPageTowardTop = { distance ->
            val page = _binding?.root
            val before = page?.scrollY ?: 0
            page?.scrollTo(0, (before - distance).coerceAtLeast(0))
            before - (page?.scrollY ?: 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mineHeader) { _, insets ->
            topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            configureExpansion()
            updateHeaderStatusBar()
            insets
        }
        ViewCompat.requestApplyInsets(binding.mineHeader)
        carousel = HeaderCarousel(
            Glide.with(this), images, slideshow, availability, localIndices,
            binding.headerImage, binding.headerNextImage, { binding.mineHeader.expandedHeightPx }, ::updatePlayback,
        )
        binding.mineHeader.listener = object : HeaderGestureLayout.Listener {
            override fun onTouchStart() { carousel?.beginTouch() }
            override fun onHorizontalDrag(offset: Float) { carousel?.drag(offset) }
            override fun onTouchFinish(switchImage: Boolean) { carousel?.finishTouch(switchImage) }
            override fun onExpandedChanged(expanded: Boolean) { headerExpanded = expanded }
        }
        windowRoot?.addOnLayoutChangeListener(windowLayoutListener)
        binding.headerPlayback.setOnClickListener { carousel?.togglePause() }
        binding.headerExpand.setOnClickListener {
            val index = carousel?.prepareViewer() ?: return@setOnClickListener
            val order = slideshow.previewOrder()
            viewerLaunched = true
            startActivity(
                HeaderImageActivity.intent(
                    requireContext(), order.map(images::get), order.indexOf(index), cacheOnly = !availability.useRemote,
                ),
            )
        }
        updatePlayback()
        val themePreferenceStore = ThemePreferenceStore(requireContext())
        refreshPresentation(themePreferenceStore)
        binding.historyButton.setOnClickListener { startActivity(Intent(requireContext(), HistoryActivity::class.java)) }
        binding.collectionButton.setOnClickListener { startActivity(Intent(requireContext(), CollectionActivity::class.java)) }
        binding.hotButton.setOnClickListener { startActivity(Intent(requireContext(), HotActivity::class.java)) }
        binding.downloadCountRow.setOnClickListener {
            requireContext().showDownloadCountDialog { refreshDownloadSettings() }
        }
        binding.mobileDownloadSwitch.setOnCheckedChangeListener { _, allowed ->
            val repository = AppGraph.downloadRepository
            if (repository.mobileDataAllowed != allowed) repository.setMobileDataAllowed(allowed)
        }
        binding.themeSettingsButton.setOnClickListener {
            val modes = ThemeMode.entries
            requireContext().createOptionsSheet(
                getString(R.string.theme_settings_title),
                modes.map { getString(it.labelRes) },
                selectedIndex = modes.indexOf(themePreferenceStore.currentMode()),
            ) { index -> themePreferenceStore.select(modes[index]) }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            observeHeaderNetwork()
            carousel?.updateNetwork(connectivity.hasInternetNetwork(), retryOnEntry = hasResumed && !viewerLaunched)
            carousel?.setViewerOpen(false)
            viewerLaunched = false
            hasResumed = true
            configureExpansion()
            refreshPresentation(ThemePreferenceStore(requireContext()))
            binding.headerImage.doOnLayout { if (_binding != null && isResumed) carousel?.start() }
        }
    }

    internal fun headerCoversStatusBar(): Boolean {
        val views = _binding ?: return true
        if (!views.mineHeader.isLaidOut) return true
        return views.mineHeader.bottom - views.root.scrollY >= topInset.coerceAtLeast(1)
    }

    private fun updateHeaderStatusBar() {
        val covers = headerCoversStatusBar()
        if (covers == headerWasBehindStatusBar) return
        headerWasBehindStatusBar = covers
        windowRoot?.let(ViewCompat::requestApplyInsets)
    }

    private fun observeHeaderNetwork() {
        val manager = connectivity
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = update()
            override fun onLost(network: Network) = update()

            private fun update() {
                activity?.runOnUiThread {
                    if (networkCallback === this) carousel?.updateNetwork(manager.hasInternetNetwork())
                }
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
    }

    private fun ConnectivityManager.hasInternetNetwork(): Boolean =
        getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun Bundle.restoreHeaderOrder(): HeaderSlideshow.Snapshot? {
        return HeaderSlideshow.Snapshot(
            pool = getIntArray("pool")?.toList() ?: return null,
            index = getInt("index", -1),
            history = getIntArray("history")?.toList() ?: return null,
            cursor = getInt("cursor", -1),
            remaining = getIntArray("remaining")?.toList() ?: return null,
            failures = getIntArray("failures")?.toList() ?: return null,
        )
    }

    private fun HeaderSlideshow.Snapshot.toBundle() = Bundle().apply {
        putIntArray("pool", pool.toIntArray())
        putInt("index", index)
        putIntArray("history", history.toIntArray())
        putInt("cursor", cursor)
        putIntArray("remaining", remaining.toIntArray())
        putIntArray("failures", failures.toIntArray())
    }

    private fun refreshPresentation(themePreferenceStore: ThemePreferenceStore) {
        binding.historyCount.text = AppGraph.catalogRepository.getHistory().size.toString()
        binding.favoriteCount.text = AppGraph.catalogRepository.getFavorites().size.toString()
        binding.themeSettingsSummary.setText(themePreferenceStore.currentMode().labelRes)
        refreshDownloadSettings()
    }

    private fun refreshDownloadSettings() {
        val views = _binding ?: return
        val repository = AppGraph.downloadRepository
        views.downloadCountValue.text = getString(R.string.download_parallel_option, repository.parallelDownloads)
        views.mobileDownloadSwitch.isChecked = repository.mobileDataAllowed
    }

    private fun configureExpansion(reload: Boolean = true, forceReload: Boolean = false) {
        val currentBinding = _binding ?: return
        val windowHeight = windowRoot?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val changed = currentBinding.mineHeader.configureSize(windowHeight, topInset)
        if (reload && (changed || forceReload)) {
            // Only a window change reloads the two bounded targets, never a drag.
            carousel?.stop()
            currentBinding.headerImage.doOnLayout {
                if (_binding === currentBinding && isResumed) carousel?.start()
            }
        }
    }

    override fun onPause() {
        networkCallback?.let(connectivity::unregisterNetworkCallback)
        networkCallback = null
        _binding?.mineHeader?.cancelInteraction()
        carousel?.stop()
        super.onPause()
    }

    private fun updatePlayback() {
        _binding?.let {
            it.headerPlayback.setImageResource(if (slideshow.paused) R.drawable.ic_play else R.drawable.ic_pause)
            it.headerPlayback.contentDescription = getString(
                if (slideshow.paused) R.string.mine_resume_slideshow else R.string.mine_pause_slideshow,
            )
            it.headerPlayback.isEnabled = !slideshow.exhausted
            it.headerExpand.isEnabled = carousel?.canExpand == true
        }
    }

    override fun onDestroyView() {
        windowRoot?.removeOnLayoutChangeListener(windowLayoutListener)
        windowRoot = null
        _binding?.root?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        _binding?.mineHeader?.removeOnLayoutChangeListener(headerLayoutListener)
        _binding?.mineHeader?.cancelInteraction()
        _binding?.mineHeader?.listener = null
        _binding?.mineHeader?.scrollPageTowardTop = null
        carousel?.dispose()
        carousel = null
        _binding = null
        super.onDestroyView()
    }

    private val ThemeMode.labelRes: Int
        get() = when (this) {
            ThemeMode.SYSTEM -> R.string.theme_mode_system
            ThemeMode.LIGHT -> R.string.theme_mode_light
            ThemeMode.DARK -> R.string.theme_mode_dark
        }
}
