package com.aiyifan.app.feature.download

import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail

internal data class DownloadProgressPresentation(
    val percent: Int?,
    val totalBytes: Long?,
    val segmentBased: Boolean,
    val indeterminate: Boolean,
)

internal fun downloadProgress(bytes: Long, length: Long, segmentPercent: Float, running: Boolean): DownloadProgressPresentation {
    val total = length.takeIf { it > 0 }
    val percent = if (total != null) ((bytes.coerceAtLeast(0).toDouble() / total) * 100).toInt().coerceIn(0, 100)
        else segmentPercent.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceIn(0, 100)
    return DownloadProgressPresentation(percent, total, total == null && percent != null, running && percent == null)
}

internal enum class LibraryEpisodeState {
    DOWNLOADED, NOT_DOWNLOADED, DOWNLOADING, WAITING_WIFI, WAITING_NETWORK, QUEUED, PAUSED, FAILED, REMOVING, PREPARING,
}

internal data class LibraryDownload(
    val id: String,
    val detail: VideoDetail,
    val episode: Episode,
    val order: Int,
    val bytes: Long,
    val state: LibraryEpisodeState,
)

internal data class LibraryEpisode(val episode: Episode, val state: LibraryEpisodeState, val downloadId: String?)

internal data class DownloadFilm(val detail: VideoDetail, val episodes: List<LibraryEpisode>, val completed: List<LibraryDownload>) {
    val totalBytes: Long get() = completed.sumOf { it.bytes.coerceAtLeast(0) }
    fun playId(historyEpisodeKey: String?): String? =
        completed.firstOrNull { it.episode.episodeKey == historyEpisodeKey }?.id ?: completed.firstOrNull()?.id
}

/** Snapshot order is authoritative; legacy tasks expose only episodes that are actually known. */
internal fun downloadFilms(entries: List<LibraryDownload>, snapshot: (String) -> VideoDetail?): List<DownloadFilm> =
    entries.groupBy { it.detail.mediaKey }.mapNotNull { (key, tasks) ->
        val sorted = tasks.sortedWith(compareBy<LibraryDownload> { it.order }.thenBy { it.episode.episodeKey })
        val completed = sorted.filter { it.state == LibraryEpisodeState.DOWNLOADED }
        if (completed.isEmpty()) return@mapNotNull null
        val detail = snapshot(key) ?: sorted.first().detail
        val known = (snapshot(key)?.episodes.orEmpty() + sorted.map { it.episode }).distinctBy { it.episodeKey }
        val byEpisode = sorted.associateBy { it.episode.episodeKey }
        val episodes = known.map { episode ->
            val task = byEpisode[episode.episodeKey]
            LibraryEpisode(episode, task?.state ?: LibraryEpisodeState.NOT_DOWNLOADED, task?.id)
        }
        DownloadFilm(detail, episodes, completed)
    }
