package com.aiyifan.app.feature.download

import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.model.WatchHistory

internal fun downloadPercent(bytes: Long, length: Long, segmentPercent: Float): Int? =
    if (length > 0) ((bytes.coerceAtLeast(0).toDouble() / length) * 100).toInt().coerceIn(0, 100)
    else segmentPercent.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceIn(0, 100)

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

internal data class LibraryEpisode(
    val episode: Episode,
    val state: LibraryEpisodeState,
    val downloadId: String?,
    val watchPercent: Int? = null,
)

internal fun watchPercentage(progressMs: Long, durationMs: Long): Int? =
    if (progressMs >= 0 && durationMs > 0) {
        (progressMs.toDouble() / durationMs * 100).toInt().coerceIn(0, 100)
    } else null

internal data class DownloadFilm(val detail: VideoDetail, val episodes: List<LibraryEpisode>, val completed: List<LibraryDownload>) {
    val totalBytes: Long get() = completed.sumOf { it.bytes.coerceAtLeast(0) }
    fun playId(historyEpisodeKey: String?): String? =
        completed.firstOrNull { it.episode.episodeKey == historyEpisodeKey }?.id ?: completed.firstOrNull()?.id
}

/** Snapshot order is authoritative; legacy tasks expose only episodes that are actually known. */
internal fun downloadFilms(
    entries: List<LibraryDownload>,
    snapshot: (String) -> VideoDetail?,
    history: (String, String) -> WatchHistory? = { _, _ -> null },
): List<DownloadFilm> =
    entries.groupBy { it.detail.mediaKey }.mapNotNull { (key, tasks) ->
        if (tasks.none { it.state == LibraryEpisodeState.DOWNLOADED }) return@mapNotNull null
        downloadFilm(tasks, snapshot(key), history = history)
    }

/** An open sheet keeps its original grid even if all of the video's downloads are removed. */
internal fun downloadFilm(
    entries: List<LibraryDownload>,
    snapshot: VideoDetail?,
    retained: DownloadFilm? = null,
    history: (String, String) -> WatchHistory?,
): DownloadFilm {
    val sorted = entries.sortedWith(compareBy<LibraryDownload> { it.order }.thenBy { it.episode.episodeKey })
    val detail = snapshot ?: retained?.detail ?: sorted.first().detail
    val known = (retained?.episodes.orEmpty().map { it.episode } + snapshot?.episodes.orEmpty() +
        sorted.map { it.episode }).distinctBy { it.episodeKey }
    val byEpisode = sorted.associateBy { it.episode.episodeKey }
    val episodes = known.map { episode ->
        val task = byEpisode[episode.episodeKey]
        LibraryEpisode(
            episode, task?.state ?: LibraryEpisodeState.NOT_DOWNLOADED, task?.id,
            history(detail.mediaKey, episode.episodeKey)?.let { watchPercentage(it.progressMs, it.durationMs) },
        )
    }
    return DownloadFilm(detail, episodes, sorted.filter { it.state == LibraryEpisodeState.DOWNLOADED })
}

internal fun downloadFilms(entries: List<LibraryDownload>, snapshot: (String) -> VideoDetail?): List<DownloadFilm> =
    downloadFilms(entries, snapshot, { _, _ -> null })
