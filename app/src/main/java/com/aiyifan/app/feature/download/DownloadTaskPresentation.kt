@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.feature.download

import com.aiyifan.app.R
import com.aiyifan.app.core.download.DownloadFailure
import androidx.media3.exoplayer.offline.Download
import com.aiyifan.app.core.download.STOP_REASON_WIFI
import com.aiyifan.app.core.download.DownloadTask
import com.aiyifan.app.core.download.OfflineDownloadRepository

// Keep unreadable records accessible for removal, even if Media3 considers them completed.
internal val DownloadTask.inQueue: Boolean
    get() = download.state != Download.STATE_COMPLETED || metadata == null

internal fun OfflineDownloadRepository.episodeState(task: DownloadTask): LibraryEpisodeState = when {
    task.download.state == Download.STATE_COMPLETED -> LibraryEpisodeState.DOWNLOADED
    task.download.state == Download.STATE_REMOVING || task.download.state == Download.STATE_RESTARTING -> LibraryEpisodeState.REMOVING
    task.download.stopReason == STOP_REASON_WIFI -> LibraryEpisodeState.WAITING_WIFI
    task.download.state == Download.STATE_STOPPED -> LibraryEpisodeState.PAUSED
    task.download.state == Download.STATE_FAILED -> LibraryEpisodeState.FAILED
    task.preparing -> LibraryEpisodeState.PREPARING
    task.download.state == Download.STATE_DOWNLOADING -> LibraryEpisodeState.DOWNLOADING
    manager.notMetRequirements != 0 -> LibraryEpisodeState.WAITING_NETWORK
    else -> LibraryEpisodeState.QUEUED
}

internal fun DownloadFailure?.queueMessage(): Int = when (this) {
    DownloadFailure.NO_SPACE -> R.string.download_error_space
    DownloadFailure.NETWORK -> R.string.download_error_network
    DownloadFailure.NOT_FOUND -> R.string.download_error_not_found
    DownloadFailure.SOURCE, DownloadFailure.FORMAT, DownloadFailure.DRM, DownloadFailure.TRACKS, DownloadFailure.LIVE ->
        R.string.download_row_unsupported
    else -> R.string.download_row_retry
}
