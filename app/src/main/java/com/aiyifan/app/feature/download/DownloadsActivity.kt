package com.aiyifan.app.feature.download

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.recyclerview.widget.LinearLayoutManager
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.download.DownloadTask
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityDownloadsBinding
import com.aiyifan.app.feature.video.VideoPlayerActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private val repository get() = AppGraph.downloadRepository
    private val busy = mutableSetOf<String>()
    private var selectedTab = 0
    private val activeAdapter = DownloadAdapter(::performAction, ::showMore)
    private val completedAdapter = DownloadFilmAdapter(::playFilm, ::performAction, { showEpisodes(it, false) }, { showEpisodes(it, true) })
    private var films = emptyList<DownloadFilm>()
    private var librarySheet: DownloadLibrarySheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
        binding.topBar.applySystemBarsPadding(top = true, growHeight = true)
        binding.backButton.setOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.itemAnimator = null
        selectedTab = savedInstanceState?.getInt("tab") ?: 0
        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.download_active_tab, 0)))
        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.download_completed_tab, 0)))
        binding.tabs.getTabAt(selectedTab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { selectedTab = tab.position; render() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.mobileDataSwitch.isChecked = repository.mobileDataAllowed
        binding.mobileDataSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled == repository.mobileDataAllowed) return@setOnCheckedChangeListener
            if (!enabled) repository.setMobileDataAllowed(false) else {
                binding.mobileDataSwitch.isChecked = false
                MaterialAlertDialogBuilder(this).setMessage(R.string.download_mobile_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.download_mobile_enable) { _, _ ->
                        repository.setMobileDataAllowed(true)
                        binding.mobileDataSwitch.isChecked = true
                    }.show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    repository.awaitLoaded()
                    repository.recoverFromForeground()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { showOperationFailure() }
                repository.tasks.collect { render() }
            }
        }
        repository.refresh()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val tasks = repository.tasks.value
        films = downloadFilms(tasks.mapNotNull { task ->
            task.metadata?.let { LibraryDownload(task.id, it.detail, it.episode, it.episodeOrder, task.bytesDownloaded, episodeState(task)) }
        }, repository::filmSnapshot)
        // Unreadable records remain removable instead of disappearing from the library.
        val active = tasks.filter { it.download.state != Download.STATE_COMPLETED || it.metadata == null }
        binding.tabs.getTabAt(0)?.text = getString(R.string.download_active_tab, active.size)
        binding.tabs.getTabAt(1)?.text = getString(R.string.download_completed_tab, films.size)
        val targetAdapter = if (selectedTab == 1) completedAdapter else activeAdapter
        if (binding.recycler.adapter !== targetAdapter) binding.recycler.adapter = targetAdapter
        if (selectedTab == 1) completedAdapter.submitList(films.map { DownloadFilmRow(it, repository.posterFile(it.detail.mediaKey)) })
        else activeAdapter.submitList(active.map(::row))
        binding.emptyView.isVisible = if (selectedTab == 1) films.isEmpty() else active.isEmpty()
        binding.emptyView.setText(if (selectedTab == 1) R.string.download_empty_completed else R.string.download_empty_active)
        librarySheet?.let { sheet -> sheet.update(films.firstOrNull { it.detail.mediaKey == sheet.mediaKey }) }
    }

    private fun episodeState(task: DownloadTask): LibraryEpisodeState = when {
        task.download.state == Download.STATE_COMPLETED -> LibraryEpisodeState.DOWNLOADED
        task.download.state == Download.STATE_REMOVING || task.download.state == Download.STATE_RESTARTING -> LibraryEpisodeState.REMOVING
        task.download.state == Download.STATE_STOPPED -> LibraryEpisodeState.PAUSED
        task.download.state == Download.STATE_FAILED -> LibraryEpisodeState.FAILED
        task.preparing -> LibraryEpisodeState.PREPARING
        task.download.state == Download.STATE_DOWNLOADING -> LibraryEpisodeState.DOWNLOADING
        repository.manager.notMetRequirements != 0 -> if (repository.mobileDataAllowed) LibraryEpisodeState.WAITING_NETWORK else LibraryEpisodeState.WAITING_WIFI
        else -> LibraryEpisodeState.QUEUED
    }

    private fun row(task: DownloadTask): DownloadRow {
        val download = task.download
        val metadata = task.metadata
        val state = episodeState(task)
        val status = when {
            metadata == null -> R.string.download_unreadable
            download.stopReason == OfflineDownloadRepository.STOP_REASON_TIMEOUT -> R.string.download_timeout_paused
            download.state == Download.STATE_FAILED -> task.failure?.messageRes ?: R.string.download_failed
            else -> state.label()
        }
        val action = when (download.state) {
            Download.STATE_FAILED -> R.string.download_retry
            Download.STATE_STOPPED -> R.string.download_resume
            else -> R.string.download_pause
        }
        val progress = downloadProgress(task.bytesDownloaded, download.contentLength, task.percentDownloaded,
            state == LibraryEpisodeState.DOWNLOADING || state == LibraryEpisodeState.PREPARING)
        val bytes = Formatter.formatShortFileSize(this, task.bytesDownloaded.coerceAtLeast(0))
        val size = when {
            progress.totalBytes != null -> getString(R.string.download_size_known, bytes,
                Formatter.formatShortFileSize(this, progress.totalBytes), progress.percent ?: 0)
            progress.segmentBased -> getString(R.string.download_library_segment_progress, progress.percent, bytes)
            else -> getString(R.string.download_size_unknown, bytes)
        }
        return DownloadRow(task.id, metadata?.detail?.title ?: getString(R.string.download_unknown_title),
            listOfNotNull(metadata?.episode?.episodeTitle, metadata?.episode?.resolution ?: getString(R.string.download_default_quality)).joinToString(" · "),
            getString(status), size, progress.percent, progress.indeterminate, action,
            metadata != null && task.id !in busy && download.state !in listOf(Download.STATE_REMOVING, Download.STATE_RESTARTING, Download.STATE_COMPLETED),
            task.id !in busy && download.state != Download.STATE_REMOVING,
            metadata?.detail?.mediaKey?.let(repository::posterFile))
    }

    private fun playFilm(mediaKey: String) {
        val film = films.firstOrNull { it.detail.mediaKey == mediaKey } ?: return
        val history = AppGraph.catalogRepository.getHistory().firstOrNull { it.mediaKey == mediaKey }
        film.playId(history?.episodeKey)?.let(::performAction)
    }

    private fun showEpisodes(mediaKey: String, managing: Boolean) {
        val film = films.firstOrNull { it.detail.mediaKey == mediaKey } ?: return
        librarySheet = DownloadLibrarySheet(this, mediaKey, managing, ::performAction, ::removeCompleted) { librarySheet = null }
            .also { it.show(film) }
    }

    private fun performAction(id: String) {
        val task = repository.get(id) ?: return
        when (task.download.state) {
            Download.STATE_COMPLETED -> task.metadata?.let {
                startActivity(VideoPlayerActivity.offlineIntent(this, id, it.detail.mediaKey))
            }
            Download.STATE_FAILED -> operation(id) { repository.retry(id) }
            Download.STATE_STOPPED -> operation(id) { repository.resume(id) }
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> operation(id) { repository.pause(id) }
        }
    }

    private fun showMore(id: String, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.download_delete).setOnMenuItemClickListener { confirmDelete(id); true }
        }.show()
    }

    private fun confirmDelete(id: String) {
        val task = repository.get(id) ?: return
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.download_delete_confirmation,
            task.metadata?.let { "${it.detail.title} · ${it.episode.episodeTitle}" } ?: getString(R.string.download_unknown_title)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_delete) { _, _ -> operation(id) { repository.remove(id) } }.show()
    }

    private fun removeCompleted(ids: List<String>) {
        ids.forEach { id ->
            if (repository.get(id)?.download?.state == Download.STATE_COMPLETED) operation(id) { repository.remove(id) }
        }
    }

    private fun operation(id: String, block: suspend () -> Unit) {
        if (!busy.add(id)) return
        render()
        lifecycleScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: com.aiyifan.app.core.download.DownloadNetworkUnavailableException) {
                Toast.makeText(this@DownloadsActivity, R.string.download_retry_network_unavailable, Toast.LENGTH_LONG).show()
            }
            catch (_: Exception) { showOperationFailure() }
            finally { busy.remove(id); render() }
        }
    }

    private fun showOperationFailure() {
        Toast.makeText(this, R.string.download_operation_failed, Toast.LENGTH_LONG).show()
    }
}
