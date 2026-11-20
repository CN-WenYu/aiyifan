@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import androidx.media3.exoplayer.offline.Download
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import org.json.JSONObject

/** Length prefixes preserve both original keys without delimiter collisions or URL-dependent identity. */
fun downloadId(mediaKey: String, episodeKey: String): String =
    "${mediaKey.length}:$mediaKey${episodeKey.length}:$episodeKey"

internal fun requiresDownloadReplacement(previousUrl: String, resolvedUrl: String): Boolean =
    previousUrl != resolvedUrl

data class DownloadTask(
    val download: Download,
    val metadata: DownloadMetadata?,
    // Media3 mutates DownloadProgress in place. Snapshots make StateFlow emit progress changes.
    val bytesDownloaded: Long = download.bytesDownloaded,
    val percentDownloaded: Float = download.percentDownloaded,
    val failure: DownloadFailure? = null,
    val preparing: Boolean = false,
) {
    val id: String get() = download.request.id
    internal val usableEstimatedTotalBytes: Long?
        get() = metadata?.estimatedTotalBytes?.takeIf { it > bytesDownloaded.coerceAtLeast(0) }

    internal val remainingBytes: Long?
        get() {
            val total = download.contentLength.takeIf { it > 0 } ?: usableEstimatedTotalBytes ?: return null
            return (total - bytesDownloaded.coerceAtLeast(0)).coerceAtLeast(0)
        }

    internal val queueAction: DownloadQueueAction?
        get() = if (metadata == null) null else DownloadQueueAction.forDownload(download)
}

internal enum class DownloadQueueAction {
    PAUSE, RESUME;

    companion object {
        fun forDownload(download: Download): DownloadQueueAction? = when (download.state) {
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> PAUSE
            Download.STATE_STOPPED -> if (download.stopReason == STOP_REASON_WIFI) PAUSE else RESUME
            else -> null
        }
    }
}

/** Only an offline playback snapshot is stored; favorites, history and unrelated episodes stay out. */
data class DownloadMetadata(val detail: VideoDetail, val episode: Episode, val episodeOrder: Int,
    val generation: String? = null, val estimatedTotalBytes: Long? = null) {
    fun encode(): ByteArray = JSONObject().apply {
        put("version", 2)
        put("generation", generation)
        put("estimatedTotalBytes", estimatedTotalBytes)
        put("mediaKey", detail.mediaKey)
        put("title", detail.title)
        put("coverUrl", detail.coverUrl)
        put("videoType", detail.videoType)
        put("episodeOrder", episodeOrder)
        put("episode", JSONObject().apply {
            put("episodeKey", episode.episodeKey)
            put("episodeTitle", episode.episodeTitle)
            put("uniqueId", episode.uniqueId)
            put("mediaUrl", episode.mediaUrl)
            put("resolution", episode.resolution)
            put("lang", episode.lang)
            put("duration", episode.duration)
            put("watchProgressMs", episode.watchProgressMs)
            put("opSecond", episode.opSecond)
            put("epSecond", episode.epSecond)
        })
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray): DownloadMetadata? = runCatching {
            val json = JSONObject(data.toString(Charsets.UTF_8))
            require(json.getInt("version") in 1..2)
            val value = json.getJSONObject("episode")
            val episode = Episode(
                episodeKey = value.getString("episodeKey"),
                episodeTitle = value.getString("episodeTitle"),
                uniqueId = value.getInt("uniqueId"),
                mediaUrl = value.optionalString("mediaUrl"),
                resolution = value.optionalString("resolution"),
                lang = value.optionalString("lang"),
                duration = value.optionalString("duration"),
                watchProgressMs = value.optLong("watchProgressMs", 0),
                opSecond = value.optionalLong("opSecond"),
                epSecond = value.optionalLong("epSecond"),
            )
            val detail = VideoDetail(
                mediaKey = json.getString("mediaKey"),
                title = json.getString("title"),
                coverUrl = json.getString("coverUrl"),
                videoType = json.getInt("videoType"),
                episodes = listOf(episode),
            )
            val generation = json.optionalString("generation")
            require(detail.mediaKey.isNotBlank() && episode.episodeKey.isNotBlank() &&
                (generation != null || !episode.mediaUrl.isNullOrBlank()))
            DownloadMetadata(detail, episode, json.getInt("episodeOrder"), generation,
                json.optionalLong("estimatedTotalBytes")?.takeIf { it > 0 })
        }.getOrNull()

        private fun JSONObject.optionalString(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optionalLong(key: String): Long? =
            if (has(key) && !isNull(key)) getLong(key) else null
    }
}
