@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.feature.download

import androidx.activity.OnBackPressedCallback
import com.aiyifan.app.core.ui.showDestructiveConfirmation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.recyclerview.widget.LinearLayoutManager
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.download.DownloadTask
import com.aiyifan.app.core.download.downloadId
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.databinding.FragmentDownloadLibraryBinding
import com.aiyifan.app.feature.main.MainActivity
import com.aiyifan.app.feature.video.VideoPlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DownloadLibraryFragment : Fragment(R.layout.fragment_download_library) {
    private val repository get() = AppGraph.downloadRepository
    private var films = emptyList<DownloadFilm>()
    private var sheet: DownloadLibrarySheet? = null
    private val busy = mutableSetOf<String>()

    private var views: FragmentDownloadLibraryBinding? = null
    private var editing = false
    private val selected = linkedSetOf<String>()
    private val exitEdit = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setEditing(false)
    }
    private val adapter = DownloadFilmAdapter(::playFilm, ::clickFilm) { key ->
        if (films.any { it.detail.mediaKey == key }) {
            editing = true
            selected.add(key)
            render()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentDownloadLibraryBinding.bind(view)
        views = binding
        editing = savedInstanceState?.getBoolean("editing") ?: editing
        savedInstanceState?.getStringArrayList("selectedFilms")?.let { selected.addAll(it) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, exitEdit)
        binding.editLibrary.setOnClickListener { setEditing(!editing) }
        binding.editActions.selectAll.setOnClickListener {
            if (selected.size == films.size) selected.clear() else selected.addAll(films.map { it.detail.mediaKey })
            render()
        }
        binding.editActions.removeSelected.setOnClickListener { confirmRemoveFilms() }
        binding.films.layoutManager = LinearLayoutManager(requireContext())
        binding.films.adapter = adapter
        binding.films.itemAnimator = null
        binding.queueEntry.setOnClickListener { startActivity(Intent(requireContext(), DownloadsActivity::class.java)) }
        binding.browseHome.setOnClickListener { (requireActivity() as MainActivity).selectHome() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    repository.awaitLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showFailure()
                }
                repository.tasks.collect { tasks ->
                    films = downloadFilms(tasks.mapNotNull { task ->
                        task.metadata?.let {
                            LibraryDownload(task.id, it.detail, it.episode, it.episodeOrder, task.bytesDownloaded, repository.episodeState(task))
                        }
                    }, repository::filmSnapshot, AppGraph.catalogRepository::getEpisodeHistory)
                    selected.retainAll(films.map { it.detail.mediaKey }.toSet())
                    if (films.isEmpty()) editing = false
                    render()
                    sheet?.let { current -> current.update(films.firstOrNull { it.detail.mediaKey == current.mediaKey }) }
                }
            }
        }
    }

    private fun render() {
        val binding = views ?: return
        val queue = repository.tasks.value.filter { it.inQueue }
        adapter.submitList(films.map {
            val key = it.detail.mediaKey
            DownloadFilmRow(it, repository.posterFile(key), editing, key in selected)
        })
        exitEdit.isEnabled = editing && !isHidden
        binding.editLibrary.isVisible = films.isNotEmpty()
        binding.editLibrary.setText(if (editing) R.string.download_done else R.string.download_edit)
        binding.editActions.renderSelection(editing, selected.size, films.size)
        binding.libraryHeader.isVisible = films.isNotEmpty()
        binding.filmCount.text = getString(R.string.download_film_count, films.size)
        binding.queueEntry.isVisible = !editing && queue.isNotEmpty()
        binding.queueSummary.text = queueSummary(queue)
        binding.emptyState.isVisible = films.isEmpty()
        binding.browseHome.isVisible = queue.isEmpty()
        binding.emptyMessage.setText(if (queue.isEmpty()) R.string.download_library_empty else R.string.download_library_pending)
    }

    private fun clickFilm(key: String) {
        if (editing) {
            if (films.none { it.detail.mediaKey == key }) return
            if (!selected.add(key)) selected.remove(key)
            render()
        } else showEpisodes(key)
    }

    private fun setEditing(enabled: Boolean) {
        editing = enabled
        selected.clear()
        render()
    }

    private fun confirmRemoveFilms() {
        val targets = films.filter { it.detail.mediaKey in selected }
        val ids = targets.flatMap { film -> film.completed.map { it.id } }
        if (ids.isEmpty()) return
        (requireActivity() as AppCompatActivity).showDestructiveConfirmation(
            R.string.download_library_delete_title,
            getString(R.string.download_remove_films, targets.size, ids.size),
            R.string.download_delete,
        ) {
            setEditing(false)
            remove(ids)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && editing) setEditing(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("editing", editing)
        outState.putStringArrayList("selectedFilms", ArrayList(selected))
    }

    private fun queueSummary(queue: List<DownloadTask>): String {
        fun status(task: DownloadTask) = if (task.metadata == null) R.string.download_unreadable else repository.episodeState(task).label()
        if (films.isEmpty() && queue.isNotEmpty()) {
            val first = queue.firstOrNull { it.download.state == Download.STATE_DOWNLOADING } ?: queue.first()
            return listOfNotNull(first.metadata?.detail?.title, first.metadata?.episode?.episodeTitle, getString(status(first))).joinToString(" · ")
        }
        return queue.groupingBy(::status).eachCount().entries.joinToString(" · ") { (label, count) ->
            getString(R.string.download_task_count, count, getString(label))
        }
    }

    private fun playFilm(mediaKey: String) {
        val film = films.firstOrNull { it.detail.mediaKey == mediaKey } ?: return
        val history = AppGraph.catalogRepository.getHistory().firstOrNull { it.mediaKey == mediaKey }
        film.playId(history?.episodeKey)?.let(::play)
    }

    private fun play(id: String) {
        val task = repository.get(id) ?: return
        if (task.download.state == Download.STATE_COMPLETED) task.metadata?.let {
            startActivity(VideoPlayerActivity.offlineIntent(requireContext(), id, it.detail.mediaKey))
        }
    }

    private fun showEpisodes(mediaKey: String) {
        val film = films.firstOrNull { it.detail.mediaKey == mediaKey } ?: return
        sheet = DownloadLibrarySheet(requireActivity() as AppCompatActivity, mediaKey, ::play, ::enqueue, ::remove) { sheet = null }
            .also { it.show(film) }
    }

    private fun enqueue(detail: VideoDetail, episode: Episode) {
        val id = downloadId(detail.mediaKey, episode.episodeKey)
        if (repository.get(id) == null) (requireActivity() as AppCompatActivity).confirmDownloadNetwork(setOf(id)) { once ->
            operation(id) { repository.enqueue(detail, episode, once) }
        }
    }

    private fun remove(ids: List<String>) {
        ids.filter { repository.get(it)?.download?.state == Download.STATE_COMPLETED }.forEach { id ->
            operation(id) { repository.remove(id) }
        }
    }

    private fun operation(id: String, block: suspend () -> Unit) {
        if (!busy.add(id)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { showFailure() }
            finally { busy.remove(id) }
        }
    }

    private fun showFailure() {
        Toast.makeText(requireContext(), R.string.download_operation_failed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        sheet?.dismiss()
        views?.films?.adapter = null
        views = null
        super.onDestroyView()
    }
}
