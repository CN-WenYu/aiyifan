@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.DownloadRequest
import com.aiyifan.app.core.model.VideoDetail
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Source/track and catalog snapshots only. DownloadIndex remains the sole task state store. */
internal class DownloadSnapshotStore(private val directory: File) {
    private val requests = ConcurrentHashMap<String, PreparedSource>()
    private val films = ConcurrentHashMap<String, VideoDetail>()
    data class PreparedSource(val generation: String?, val request: DownloadRequest)

    @Synchronized fun source(id: String): PreparedSource? = requests[id] ?: runCatching {
        val json = JSONObject(AtomicFile(file("source", id)).readFully().toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val keys = json.getJSONArray("keys")
        val request = DownloadRequest.Builder(id, Uri.parse(json.getString("uri")))
            .setMimeType(json.optionalText("mime"))
            .setCustomCacheKey(json.optionalText("cacheKey"))
            .setStreamKeys(List(keys.length()) { index -> keys.getJSONArray(index).let { StreamKey(it.getInt(0), it.getInt(1), it.getInt(2)) } })
            .setData(json.getString("metadata").toByteArray(Charsets.UTF_8)).build()
        require(DownloadMetadata.decode(request.data) != null)
        PreparedSource(json.optionalText("generation"), request).also { requests[id] = it }
    }.getOrNull()

    @Synchronized fun saveSource(generation: String?, request: DownloadRequest) {
        val json = JSONObject().put("version", 1).put("generation", generation)
            .put("uri", request.uri.toString()).put("mime", request.mimeType).put("cacheKey", request.customCacheKey)
            .put("metadata", request.data.toString(Charsets.UTF_8))
            .put("keys", JSONArray().apply { request.streamKeys.forEach { put(JSONArray(listOf(it.periodIndex, it.groupIndex, it.streamIndex))) } })
        write(file("source", request.id), json.toString().toByteArray(Charsets.UTF_8))
        requests[request.id] = PreparedSource(generation, request)
    }

    @Synchronized fun removeSource(id: String) {
        AtomicFile(file("source", id)).delete()
        requests.remove(id)
    }

    @Synchronized fun saveFilm(detail: VideoDetail) {
        val previous = film(detail.mediaKey)
        // Keep known episode order, also for callers that only possess one episode's snapshot.
        val updated = detail.episodes.associateBy { it.episodeKey }
        val base = if (previous != null && detail.episodes.size <= previous.episodes.size)
            previous.episodes else detail.episodes
        val merged = detail.copy(episodes = (base.map { updated[it.episodeKey] ?: it } +
            detail.episodes + previous?.episodes.orEmpty()).distinctBy { it.episodeKey })
        val json = JSONObject().put("version", 1).put("type", merged.typeName).put("actor", merged.actor)
            .put("director", merged.director).put("year", merged.publishTime).put("update", merged.updateMsg)
            .put("episodes", JSONArray().apply { merged.episodes.forEachIndexed { index, episode ->
                put(JSONObject(DownloadMetadata(merged, episode.copy(mediaUrl = null), index, "catalog").encode().toString(Charsets.UTF_8)))
            } })
        write(file("film", detail.mediaKey), json.toString().toByteArray(Charsets.UTF_8))
        films[detail.mediaKey] = merged
    }

    @Synchronized fun film(mediaKey: String): VideoDetail? = films[mediaKey] ?: runCatching {
        val json = JSONObject(AtomicFile(file("film", mediaKey)).readFully().toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val episodes = json.getJSONArray("episodes")
        val values = List(episodes.length()) { DownloadMetadata.decode(episodes.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))!! }
        values.first().detail.copy(episodes = values.map { it.episode }, typeName = json.optionalText("type"),
            actor = json.optionalText("actor"), director = json.optionalText("director"),
            publishTime = json.optionalText("year"), updateMsg = json.optionalText("update")).also { films[mediaKey] = it }
    }.getOrNull()

    fun cachedFilm(mediaKey: String): VideoDetail? = films[mediaKey]
    fun poster(mediaKey: String): File? = file("poster", mediaKey).takeIf { it.isFile }
    @Synchronized fun savePoster(mediaKey: String, bytes: ByteArray) = write(file("poster", mediaKey), bytes)
    @Synchronized fun removeFilm(mediaKey: String) {
        AtomicFile(file("film", mediaKey)).delete()
        AtomicFile(file("poster", mediaKey)).delete()
        films.remove(mediaKey)
    }

    private fun file(kind: String, id: String) = File(directory, "$kind-${digest(id)}.json")
    private fun write(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun JSONObject.optionalText(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
