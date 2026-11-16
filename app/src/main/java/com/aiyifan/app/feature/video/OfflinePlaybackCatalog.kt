package com.aiyifan.app.feature.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail

/** The player sees only completed downloads with readable local metadata. */
interface OfflinePlaybackCatalog {
    fun get(id: String): OfflinePlaybackEntry?
    fun completed(mediaKey: String): List<OfflinePlaybackEntry>
}

data class OfflinePlaybackEntry(
    val id: String,
    val detail: VideoDetail,
    val episode: Episode,
    val episodeOrder: Int,
)

@OptIn(UnstableApi::class)
internal class RepositoryOfflinePlaybackCatalog(
    private val repository: OfflineDownloadRepository,
) : OfflinePlaybackCatalog {
    override fun get(id: String): OfflinePlaybackEntry? {
        val task = repository.get(id) ?: return null
        if (task.download.state != Download.STATE_COMPLETED) return null
        val metadata = task.metadata ?: return null
        return OfflinePlaybackEntry(task.id, metadata.detail, metadata.episode, metadata.episodeOrder)
    }

    override fun completed(mediaKey: String): List<OfflinePlaybackEntry> =
        repository.completed(mediaKey).mapNotNull { get(it.id) }.sortedBy { it.episodeOrder }
}
