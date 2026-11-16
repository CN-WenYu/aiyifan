package com.aiyifan.app.feature.download

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.download.DownloadTask
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.ui.applySystemBarsPadding
import com.aiyifan.app.core.ui.setupEdgeToEdge
import com.aiyifan.app.databinding.ActivityDownloadsBinding
import com.aiyifan.app.databinding.ItemDownloadBinding
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
    private val adapter = DownloadAdapter(::performAction, ::confirmDelete)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(left = true, right = true, bottom = true)
        binding.topBar.applySystemBarsPadding(top = true, growHeight = true)
        binding.backButton.setOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
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
                try { repository.recoverFromForeground() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { Toast.makeText(this@DownloadsActivity, R.string.download_operation_failed, Toast.LENGTH_LONG).show() }
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
        val completed = tasks.count { it.download.state == Download.STATE_COMPLETED }
        binding.tabs.getTabAt(0)?.text = getString(R.string.download_active_tab, tasks.size - completed)
        binding.tabs.getTabAt(1)?.text = getString(R.string.download_completed_tab, completed)
        val visible = tasks.filter { (it.download.state == Download.STATE_COMPLETED) == (selectedTab == 1) }
        adapter.submitList(visible.map(::row))
        binding.emptyView.isVisible = visible.isEmpty()
        binding.emptyView.setText(if (selectedTab == 1) R.string.download_empty_completed else R.string.download_empty_active)
    }

    private fun row(task: DownloadTask): DownloadRow {
        val download = task.download
        val metadata = task.metadata
        val status = when {
            metadata == null -> R.string.download_unreadable
            download.state == Download.STATE_COMPLETED -> R.string.download_completed
            download.state == Download.STATE_REMOVING || download.state == Download.STATE_RESTARTING -> R.string.download_removing
            download.stopReason == OfflineDownloadRepository.STOP_REASON_TIMEOUT -> R.string.download_timeout_paused
            download.state == Download.STATE_STOPPED -> R.string.download_paused
            download.state == Download.STATE_FAILED -> task.failure?.messageRes ?: R.string.download_failed
            download.state == Download.STATE_DOWNLOADING -> R.string.download_running
            repository.manager.notMetRequirements != 0 -> if (repository.mobileDataAllowed) R.string.download_waiting_network else R.string.download_waiting_wifi
            else -> R.string.download_queued
        }
        val action = when (download.state) {
            Download.STATE_COMPLETED -> R.string.download_play
            Download.STATE_FAILED -> R.string.download_retry
            Download.STATE_STOPPED -> R.string.download_resume
            else -> R.string.download_pause
        }
        val percent = if (download.contentLength > 0 && task.percentDownloaded >= 0) task.percentDownloaded.toInt().coerceIn(0, 100) else null
        val bytes = Formatter.formatShortFileSize(this, task.bytesDownloaded)
        val size = if (percent == null) getString(R.string.download_size_unknown, bytes) else getString(
            R.string.download_size_known, bytes, Formatter.formatShortFileSize(this, download.contentLength), percent)
        return DownloadRow(task.id, metadata?.detail?.title ?: getString(R.string.download_unknown_title),
            listOfNotNull(metadata?.episode?.episodeTitle, metadata?.episode?.resolution ?: getString(R.string.download_default_quality)).joinToString(" · "),
            getString(status), size, percent, download.state == Download.STATE_DOWNLOADING,
            action, metadata != null && task.id !in busy && download.state !in listOf(Download.STATE_REMOVING, Download.STATE_RESTARTING),
            task.id !in busy && download.state != Download.STATE_REMOVING)
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

    private fun confirmDelete(id: String) {
        val task = repository.get(id) ?: return
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.download_delete_confirmation,
            task.metadata?.let { "${it.detail.title} · ${it.episode.episodeTitle}" } ?: getString(R.string.download_unknown_title)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_delete) { _, _ -> operation(id) { repository.remove(id) } }.show()
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
            catch (_: Exception) { Toast.makeText(this@DownloadsActivity, R.string.download_operation_failed, Toast.LENGTH_LONG).show() }
            finally { busy.remove(id); render() }
        }
    }
}

private data class DownloadRow(val id: String, val title: String, val subtitle: String, val status: String,
    val size: String, val percent: Int?, val running: Boolean, val action: Int, val enabled: Boolean, val canDelete: Boolean)

private class DownloadAdapter(private val action: (String) -> Unit, private val delete: (String) -> Unit) :
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
            title.text = row.title; subtitle.text = row.subtitle; status.text = row.status; size.text = row.size
            progress.isIndeterminate = row.running && row.percent == null
            progress.progress = row.percent ?: 0
            progress.isVisible = row.action != R.string.download_play
            actionButton.setText(row.action); actionButton.isEnabled = row.enabled
            deleteButton.isEnabled = row.canDelete
            actionButton.setOnClickListener { action(row.id) }
            deleteButton.setOnClickListener { delete(row.id) }
        }
    }
}
