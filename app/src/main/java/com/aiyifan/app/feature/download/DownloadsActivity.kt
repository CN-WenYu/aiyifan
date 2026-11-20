package com.aiyifan.app.feature.download

import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.recyclerview.widget.LinearLayoutManager
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import androidx.activity.OnBackPressedCallback
import com.aiyifan.app.core.download.DownloadQueueAction
import com.aiyifan.app.core.download.DownloadTask
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.ui.showDestructiveConfirmation
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupPageSystemBars
import com.aiyifan.app.databinding.ActivityDownloadsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private val repository get() = AppGraph.downloadRepository
    private val busy = mutableSetOf<String>()
    private val selected = linkedSetOf<String>()
    private var editing = false
    private var loaded = false
    private val exitEdit = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setEditing(false)
    }
    private val activeAdapter = DownloadAdapter(::clickTask) { id ->
        if (id in selectableIds()) {
            editing = true
            selected.add(id)
            render()
        }
    }

    private fun clickTask(id: String) {
        if (editing) {
            if (id !in selectableIds()) return
            if (!selected.add(id)) selected.remove(id)
            render()
        } else if (id !in busy && repository.get(id)?.canBatchControl == true) {
            performAction(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (StartupSession.mode != AppMode.ONLINE) {
            super.onCreate(null)
            StartupActivity.restart(this)
            return
        }
        super.onCreate(savedInstanceState)
        setupPageSystemBars()
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
        binding.topBar.applySystemBarsPadding(top = true, growHeight = true)
        binding.backButton.setOnClickListener { finish() }
        editing = savedInstanceState?.getBoolean("editing") == true
        savedInstanceState?.getStringArrayList("selected")?.let(selected::addAll)
        onBackPressedDispatcher.addCallback(this, exitEdit)
        binding.editButton.setOnClickListener { setEditing(!editing) }
        binding.editActions.selectAll.setOnClickListener {
            val ids = selectableIds()
            if (selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            render()
        }
        binding.editActions.removeSelected.setOnClickListener {
            val ids = selected.toList()
            showDestructiveConfirmation(R.string.download_delete_title, getString(R.string.download_remove_confirm, ids.size),
                R.string.download_delete) {
                setEditing(false)
                ids.filter { repository.get(it)?.inQueue == true }.forEach { id -> operation(id) { repository.remove(id) } }
            }
        }
        binding.parallelButton.setOnClickListener { showDownloadCountDialog(::render) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = activeAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    repository.awaitLoaded()
                    repository.recoverFromForeground()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { showOperationFailure() }
                loaded = true
                launch {
                    while (true) {
                        val (used, remaining) = repository.storageBytes()
                        binding.storage.text = getString(R.string.download_storage,
                            Formatter.formatShortFileSize(this@DownloadsActivity, used),
                            Formatter.formatShortFileSize(this@DownloadsActivity, remaining))
                        kotlinx.coroutines.delay(1_000)
                    }
                }
                repository.tasks.collect { render() }
            }
        }
        repository.refresh()
        render()
    }

    private fun render() {
        val active = repository.tasks.value.filter { it.inQueue }
        val ids = selectableIds()
        if (loaded) selected.retainAll(ids)
        exitEdit.isEnabled = editing
        binding.backButton.isVisible = !editing
        binding.editButton.setText(if (editing) R.string.download_done else R.string.download_edit)
        binding.editButton.isEnabled = editing || ids.isNotEmpty()
        binding.batchActions.isVisible = !editing
        binding.editActions.renderSelection(editing, selected.size, ids.size)
        val batch = active.filter { it.canBatchControl }
        val pauseAll = batch.isNotEmpty() && batch.none { it.canStart }
        binding.startAllButton.apply {
            setText(if (pauseAll) R.string.download_pause_all else R.string.download_start_all)
            setIconResource(if (pauseAll) R.drawable.ic_pause else R.drawable.ic_play)
            isEnabled = batch.isNotEmpty() && batch.none { it.id in busy }
            setOnClickListener {
                val targets = batch.map { it.id }.toSet()
                if (pauseAll) repository.applyQueueAction(DownloadQueueAction.PAUSE, targets) else startTasks(targets)
            }
        }
        binding.parallelButton.text = getString(R.string.download_parallel, repository.parallelDownloads)
        activeAdapter.submitList(active.map(::row))
        binding.emptyView.isVisible = active.isEmpty()
    }

    private fun row(task: DownloadTask): DownloadRow {
        val download = task.download
        val metadata = task.metadata
        val state = repository.episodeState(task)
        val status = when {
            metadata == null -> R.string.download_unreadable
            download.stopReason == OfflineDownloadRepository.STOP_REASON_TIMEOUT -> R.string.download_timeout_paused
            download.state == Download.STATE_FAILED -> task.failure.queueMessage()
            state == LibraryEpisodeState.QUEUED -> R.string.download_row_queued
            state == LibraryEpisodeState.WAITING_WIFI -> R.string.download_row_wait_wifi
            state == LibraryEpisodeState.DOWNLOADING -> R.string.download_row_running
            else -> state.label()
        }
        val action = when (download.state) {
            Download.STATE_FAILED -> R.string.download_retry
            Download.STATE_STOPPED -> if (task.queueAction == DownloadQueueAction.PAUSE) R.string.download_pause else R.string.download_resume
            else -> R.string.download_pause
        }
        val statusColor = when {
            metadata == null || download.state == Download.STATE_FAILED -> R.color.destructive_text
            state == LibraryEpisodeState.QUEUED -> R.color.download_queued_text
            state == LibraryEpisodeState.DOWNLOADING -> R.color.accent
            else -> R.color.text_secondary
        }
        val percent = downloadPercent(task.bytesDownloaded, download.contentLength, task.percentDownloaded)
        val running = state == LibraryEpisodeState.DOWNLOADING
        val detail = when {
            running && percent != null -> getString(R.string.download_row_percent, percent)
            task.bytesDownloaded > 0 && state in listOf(LibraryEpisodeState.PAUSED, LibraryEpisodeState.DOWNLOADING) ->
                getString(R.string.download_size_unknown, Formatter.formatShortFileSize(this, task.bytesDownloaded))
            else -> ""
        }
        return DownloadRow(task.id, metadata?.detail?.title ?: getString(R.string.download_unknown_title),
            metadata?.episode?.episodeTitle.orEmpty(), getString(status), statusColor, detail,
            percent.takeIf { running }, action,
            metadata != null && task.id !in busy && download.state !in listOf(Download.STATE_REMOVING, Download.STATE_RESTARTING, Download.STATE_COMPLETED),
            task.id !in busy && download.state != Download.STATE_REMOVING,
            metadata?.detail?.mediaKey?.let(repository::posterFile), editing, task.id in selected)
    }

    private fun performAction(id: String) {
        val task = repository.get(id) ?: return
        if (task.queueAction == DownloadQueueAction.PAUSE) operation(id) { repository.pause(id) }
        else if (task.canStart) startTasks(setOf(id))
    }

    private fun setEditing(enabled: Boolean) {
        editing = enabled
        selected.clear()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("editing", editing)
        outState.putStringArrayList("selected", ArrayList(selected))
    }

    private fun selectableIds() = repository.tasks.value.filter {
        it.inQueue && it.download.state != Download.STATE_REMOVING && it.id !in busy
    }.map { it.id }.toSet()

    private val DownloadTask.canBatchControl: Boolean
        get() = queueAction != null || (metadata != null && download.state == Download.STATE_FAILED)

    private val DownloadTask.canStart: Boolean
        get() = metadata != null && download.state in listOf(Download.STATE_FAILED, Download.STATE_STOPPED)

    private fun startTasks(ids: Set<String>) {
        fun startable(id: String) = id !in busy && repository.get(id)?.canStart == true
        val targets = ids.filterTo(linkedSetOf(), ::startable)
        confirmDownloadNetwork(targets, ::startable) { once ->
            val current = targets.filter(::startable)
            if (once) repository.allowOnce(current)
            current.forEach { id ->
                when (repository.get(id)?.download?.state) {
                    Download.STATE_FAILED -> operation(id) { repository.retry(id) }
                    Download.STATE_STOPPED -> operation(id) { repository.resume(id) }
                }
            }
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
