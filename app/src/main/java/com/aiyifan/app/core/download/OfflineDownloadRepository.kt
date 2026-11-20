@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.aiyifan.app.R
import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.feature.download.VideoDownloadService
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Application-scoped owner; the Media3 index is the sole persistent task state. */
class OfflineDownloadRepository(
    context: Context,
    private val catalog: CatalogRepository,
    private val beforeRemove: (String) -> Unit = {},
) {
    private val context = context.applicationContext
    private val runtime = DownloadRuntime.get(this.context)
    val manager: DownloadManager = runtime.manager
    private val preferences = this.context.getSharedPreferences("offline_download_settings", Context.MODE_PRIVATE)
    // Error annotations only; task existence, state and progress always come from DownloadIndex.
    private val failureNotes = this.context.getSharedPreferences("offline_download_failures", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun mutation(id: String) = runtime.mutations.getOrPut(id) { Mutex() }
    private val refreshLock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()
    val mobileDataAllowed get() = runtime.policy.always
    internal fun needsMobileConsent(ids: Collection<String>) =
        runtime.policy.needsConsent && ids.any { !runtime.policy.authorized(it) }

    val parallelDownloads get() = manager.maxParallelDownloads
    fun setParallelDownloads(count: Int) {
        require(count in 1..3)
        manager.maxParallelDownloads = count
        preferences.edit().putInt("parallel_downloads", count).apply()
    }

    suspend fun storageBytes() = withContext(Dispatchers.IO) {
        runtime.cache.cacheSpace to android.os.StatFs(context.filesDir.path).availableBytes
    }

    internal fun clearOnce(ids: Collection<String>) {
        ids.forEach { runtime.policy.grantOnce(it, false) }
        runtime.policy.reconcile(manager)
    }

    internal fun allowOnce(ids: Collection<String>) {
        ids.forEach { runtime.policy.grantOnce(it, true) }
        runtime.policy.reconcile(manager)
    }

    init {
        checkMainThread()
        if (runtime.defaultPreparer == null) runtime.defaultPreparer = ::resolveAndPrepare
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                initialized.complete(Unit)
                refresh()
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) {
                    failureNotes.edit().putString(download.request.id, DownloadFailure.from(finalException).name).apply()
                } else if (download.state != Download.STATE_FAILED) {
                    failureNotes.edit().remove(download.request.id).apply()
                }
                refresh()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                failureNotes.edit().remove(download.request.id).apply()
                refresh()
            }
        })
        if (manager.isInitialized) initialized.complete(Unit)
        refresh()
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) refreshNow()
            }
        }
    }

    internal fun reconcileNetwork() = runtime.policy.reconcile(manager)

    fun setMobileDataAllowed(allowed: Boolean) {
        checkMainThread()
        preferences.edit().putBoolean("mobile_data_allowed", allowed).apply()
        runtime.policy.reconcile(manager)
        refresh()
    }

    suspend fun enqueue(detail: VideoDetail, episode: Episode, allowMobileOnce: Boolean = false): String = mutation(downloadId(detail.mediaKey, episode.episodeKey)).withLock {
        withContext(Dispatchers.Main.immediate) {
            initialized.await()
            val id = downloadId(detail.mediaKey, episode.episodeKey)
            if (readDownload(id) != null) return@withContext id
            val metadata = DownloadMetadata(detail, episode, detail.episodes.indexOfFirst { it.episodeKey == episode.episodeKey }.coerceAtLeast(0), java.util.UUID.randomUUID().toString())
            runtime.filmMutation(detail.mediaKey).withLock {
                withContext(Dispatchers.IO) { runtime.snapshots.saveFilm(detail) }
                val request = deferredRequest(id, metadata)
                runtime.preparers[id] = ::resolveAndPrepare
                withContext(NonCancellable) {
                    startService()
                    runtime.policy.grantOnce(id, allowMobileOnce)
                    manager.addDownload(request, runtime.policy.stopReason(id))
                    manager.resumeDownloads()
                    awaitDownload(id) { it != null }
                    refreshNow()
                }
            }
            id
        }
    }

    fun pause(id: String) = applyQueueAction(DownloadQueueAction.PAUSE, setOf(id))

    fun resume(id: String) = applyQueueAction(DownloadQueueAction.RESUME, setOf(id))

    internal fun applyQueueAction(action: DownloadQueueAction, ids: Set<String>) {
        checkMainThread()
        // A task may have finished or been removed since the list was rendered.
        val targets = manager.currentDownloads.filter {
            it.request.id in ids && (DownloadQueueAction.forDownload(it) == action ||
                (action == DownloadQueueAction.RESUME && it.stopReason == STOP_REASON_WIFI))
        }
        if (targets.isEmpty()) return
        val resume = action == DownloadQueueAction.RESUME
        if (resume) startService()
        targets.forEach {
            if (!resume) runtime.policy.grantOnce(it.request.id, false)
            manager.setStopReason(it.request.id, if (resume) runtime.policy.stopReason(it.request.id) else STOP_REASON_USER)
        }
        if (resume) manager.resumeDownloads()
        // The manager listener publishes the resulting states, including downloads waiting for Wi-Fi.
    }

    suspend fun retry(id: String) = mutation(id).withLock {
        withContext(Dispatchers.Main.immediate) {
            initialized.await()
            val previous = readDownload(id) ?: return@withContext
            if (previous.state == Download.STATE_COMPLETED) return@withContext
            val metadata = DownloadMetadata.decode(previous.request.data)
                ?: throw IOException(context.getString(R.string.download_error_metadata))
            // Preserve the previous resolved request/cache until fresh resolution succeeds.
            if (!isDeferred(previous.request)) withContext(Dispatchers.IO) { runtime.snapshots.saveSource(null, previous.request) }
            val request = deferredRequest(id, metadata.copy(generation = java.util.UUID.randomUUID().toString(), estimatedTotalBytes = null))
            runtime.preparers[id] = ::resolveAndPrepare
            withContext(NonCancellable) {
                startService()
                manager.addDownload(request, runtime.policy.stopReason(id))
                manager.resumeDownloads()
                awaitDownload(id) { it != null && it.request.data.contentEquals(request.data) && it.state != Download.STATE_REMOVING }
                refreshNow()
            }
        }
    }

    suspend fun remove(id: String) = mutation(id).withLock {
        // Once requested, removal continues to a persisted Media3 removal even if a screen closes.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            initialized.await()
            val previous = readDownload(id) ?: return@withContext
            val mediaKey = DownloadMetadata.decode(previous.request.data)?.detail?.mediaKey
            runtime.filmMutation(mediaKey ?: id).withLock {
                startService()
                removeAndAwait(id)
                runtime.policy.grantOnce(id, false)
                refreshNow()
                if (mediaKey != null && tasks.value.none { it.metadata?.detail?.mediaKey == mediaKey }) {
                    withContext(Dispatchers.IO) { runtime.snapshots.removeFilm(mediaKey) }
                }
            }
        }
    }

    suspend fun awaitLoaded() = withContext(Dispatchers.Main.immediate) { refreshNow() }

    fun get(id: String): DownloadTask? = tasks.value.firstOrNull { it.id == id }

    fun filmSnapshot(mediaKey: String): VideoDetail? = runtime.snapshots.cachedFilm(mediaKey)
    fun rememberFilm(detail: VideoDetail) {
        scope.launch {
            runtime.filmMutation(detail.mediaKey).withLock {
                awaitLoaded()
                if (tasks.value.any { it.metadata?.detail?.mediaKey == detail.mediaKey }) {
                    try {
                        withContext(Dispatchers.IO) { runtime.snapshots.saveFilm(detail) }
                        refreshNow()
                    } catch (_: IOException) { /* Existing offline entries remain usable without enrichment. */ }
                }
            }
        }
    }
    fun posterFile(mediaKey: String): File? = runtime.snapshots.poster(mediaKey)
    fun offlineRequest(id: String): DownloadRequest {
        val task = requireNotNull(get(id))
        check(task.download.state == Download.STATE_COMPLETED)
        if (!isDeferred(task.download.request)) return task.download.request
        val prepared = requireNotNull(runtime.snapshots.source(id)) { "Offline source snapshot is missing" }
        check(prepared.generation == DownloadMetadata.decode(task.download.request.data)?.generation)
        return prepared.request
    }

    fun completed(mediaKey: String): List<DownloadTask> = tasks.value.filter {
        it.download.state == Download.STATE_COMPLETED && it.metadata?.detail?.mediaKey == mediaKey
    }.sortedBy { it.metadata?.episodeOrder }

    /** No upstream and no writer: missing cache data fails locally without any network fallback. */
    fun offlineDataSourceFactory(downloadId: String): DataSource.Factory = CacheDataSource.Factory()
        .setCache(runtime.cache)
        .setCacheKeyFactory(taskCacheKeys(downloadId))
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)

    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Called from a visible screen, where Android permits starting the foreground service. */
    suspend fun recoverFromForeground() = withContext(Dispatchers.Main.immediate) {
        awaitLoaded()
        if (tasks.value.any {
                it.download.stopReason == Download.STOP_REASON_NONE &&
                    it.download.state in listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_REMOVING, Download.STATE_RESTARTING)
            }) {
            startService()
            manager.resumeDownloads()
        }
    }

    fun pauseForTimeout() {
        checkMainThread()
        // Global pause suppresses helper restart immediately; per-item reasons survive process death.
        manager.pauseDownloads()
        manager.currentDownloads.filter {
            it.stopReason == Download.STOP_REASON_NONE &&
                (it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING)
        }.forEach { manager.setStopReason(it.request.id, STOP_REASON_TIMEOUT) }
        refresh()
    }

    private suspend fun refreshNow() = refreshLock.withLock {
        initialized.await()
        val persisted = try {
            withContext(Dispatchers.IO) {
                manager.downloadIndex.getDownloads().use { cursor ->
                    buildList<Download> { while (cursor.moveToNext()) add(cursor.download) }
                }
            }
        } catch (_: IOException) {
            return@withLock
        }
        val active = manager.currentDownloads.associateBy { it.request.id }
        mutableTasks.value = withContext(Dispatchers.IO) {
            persisted.map { active[it.request.id] ?: it }.map { download ->
                val original = DownloadMetadata.decode(download.request.data)
                val prepared = if (isDeferred(download.request)) runtime.snapshots.source(download.request.id)
                    ?.takeIf { it.generation == original?.generation }?.request else download.request
                val metadata = prepared?.let { DownloadMetadata.decode(it.data) } ?: original
                metadata?.detail?.mediaKey?.let(runtime.snapshots::film)
                DownloadTask(download, metadata,
                    percentDownloaded = if (prepared == null) -1f else download.percentDownloaded,
                    failure = failureNotes.getString(download.request.id, null)
                    ?.let { name -> DownloadFailure.entries.firstOrNull { it.name == name } },
                    preparing = prepared == null && download.state == Download.STATE_DOWNLOADING)
            }.sortedByDescending { it.download.startTimeMs }
        }
    }

    private suspend fun readDownload(id: String): Download? =
        withContext(Dispatchers.IO) { manager.downloadIndex.getDownload(id) }

    private suspend fun awaitDownload(id: String, predicate: (Download?) -> Boolean) {
        // Reads are off the main thread. A timed-out deletion remains STATE_REMOVING in Media3.
        withTimeout(30_000) {
            while (!predicate(readDownload(id))) delay(50)
        }
    }

    private suspend fun removeAndAwait(id: String) {
        beforeRemove(id)
        manager.removeDownload(id)
        awaitDownload(id) { it == null }
        // Remove orphan spans too if a damaged/missing manifest prevented Media3 from listing them.
        withContext(Dispatchers.IO) {
            runtime.cache.keys.filter { it.startsWith(taskCachePrefix(id)) }.forEach(runtime.cache::removeResource)
            runtime.snapshots.removeSource(id)
        }
        runtime.preparers.remove(id)
    }

    private fun startService() {
        DownloadService.startForeground(context, VideoDownloadService::class.java)
    }

    private fun deferredRequest(id: String, metadata: DownloadMetadata) = DownloadRequest.Builder(id,
        Uri.Builder().scheme("offline-task").authority("queued").appendPath(id).build()).setData(metadata.encode()).build()

    private suspend fun resolveAndPrepare(request: DownloadRequest): DownloadRequest = withContext(Dispatchers.Main.immediate) {
        val original = DownloadMetadata.decode(request.data) ?: throw IOException("Missing download metadata")
        try {
            runtime.policy.enforce(request.id)
            val resolved = catalog.resolveDownload(original.detail, original.episode)
            val prepared = prepareRequest(request.id, original.copy(episode = resolved))
            runtime.policy.enforce(request.id)
            cachePoster(original.detail)
            prepared
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: DownloadPreparationException) { throw error }
        catch (error: Exception) { throw DownloadPreparationException(error) }
    }

    private suspend fun cachePoster(detail: VideoDetail) = withContext(Dispatchers.IO) {
        if (runtime.snapshots.poster(detail.mediaKey) != null || !detail.coverUrl.startsWith("https://")) return@withContext
        try {
            val connection = java.net.URL(detail.coverUrl).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                if (connection.responseCode !in 200..299) return@withContext
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2 * 1024 * 1024) return@withContext
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) runtime.snapshots.savePoster(detail.mediaKey, bytes)
            } finally { connection.disconnect() }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { /* A poster failure never fails the video download. */ }
    }

    private suspend fun prepareRequest(id: String, metadata: DownloadMetadata): DownloadRequest {
        val uri = Uri.parse(metadata.episode.mediaUrl)
        requireDownload(uri.scheme == "https" || uri.scheme == "http", DownloadFailure.SOURCE)
        val type = Util.inferContentType(uri)
        requireDownload(type == C.CONTENT_TYPE_HLS || type == C.CONTENT_TYPE_OTHER, DownloadFailure.FORMAT)
        val item = MediaItem.Builder().setUri(uri).apply {
            if (type == C.CONTENT_TYPE_HLS) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val parameters = DownloadHelper.getDefaultTrackSelectorParameters(context).buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setForceHighestSupportedBitrate(true)
            .build()
        // Also prepare progressive sources so encrypted MP4 tracks are rejected before enqueueing.
        val helper = DownloadHelper(
            item,
            DefaultMediaSourceFactory(runtime.policy.upstream(id, runtime.upstream))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
                .createMediaSource(item),
            parameters,
            DefaultRendererCapabilitiesList.Factory(DefaultRenderersFactory(context)).createRendererCapabilitiesList(),
        )
        try {
            return withTimeout(30_000) {
                suspendCancellableCoroutine { continuation ->
                    helper.prepare(object : DownloadHelper.Callback {
                        override fun onPrepared(helper: DownloadHelper) {
                            if (!continuation.isActive) return
                            try {
                                val manifest = helper.manifest as? HlsManifest
                                requireDownload(manifest == null || manifest.mediaPlaylist.hasEndTag, DownloadFailure.LIVE)
                                requireDownload(manifest == null || (manifest.mediaPlaylist.protectionSchemes == null &&
                                    manifest.multivariantPlaylist.sessionKeyDrmInitData.isEmpty()), DownloadFailure.DRM)
                                var hasSelectedAudioVideo = false
                                for (period in 0 until helper.periodCount) {
                                    val groups = helper.getTrackGroups(period)
                                    for (group in 0 until groups.length) {
                                        for (track in 0 until groups[group].length) {
                                            requireDownload(groups[group].getFormat(track).drmInitData == null, DownloadFailure.DRM)
                                        }
                                    }
                                    val mapped = helper.getMappedTrackInfo(period)
                                    for (renderer in 0 until mapped.rendererCount) {
                                        if (mapped.getRendererType(renderer) == C.TRACK_TYPE_VIDEO || mapped.getRendererType(renderer) == C.TRACK_TYPE_AUDIO) {
                                            if (helper.getTrackSelections(period, renderer).isNotEmpty()) hasSelectedAudioVideo = true
                                        }
                                    }
                                }
                                requireDownload(hasSelectedAudioVideo, DownloadFailure.TRACKS)
                                val request = helper.getDownloadRequest(id, metadata.encode())
                                // Empty HLS stream keys mean "download all", so reject instead of silently downloading every variant.
                                requireDownload(type != C.CONTENT_TYPE_HLS || request.streamKeys.isNotEmpty(), DownloadFailure.TRACKS)
                                val estimate = manifest?.estimatedBytes(request)
                                continuation.resume(if (estimate == null) request else
                                    helper.getDownloadRequest(id, metadata.copy(estimatedTotalBytes = estimate).encode()))
                            } catch (error: Exception) {
                                continuation.resumeWithException(error)
                            }
                        }
                        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(
                                DownloadPreparationException(e, if (e is DownloadHelper.LiveContentUnsupportedException)
                                    DownloadFailure.LIVE else DownloadFailure.PREPARATION),
                            )
                        }
                    })
                }
            }
        } finally {
            helper.release()
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun requireDownload(condition: Boolean, failure: DownloadFailure) {
        if (!condition) throw DownloadPreparationException(IllegalArgumentException(failure.name), failure)
    }

    companion object {
        const val STOP_REASON_USER = 1
        const val STOP_REASON_TIMEOUT = 2
    }
}

private fun HlsManifest.estimatedBytes(request: DownloadRequest): Long? {
    val variant = request.streamKeys.singleOrNull { it.groupIndex == HlsMultivariantPlaylist.GROUP_INDEX_VARIANT }
        ?: return null
    val format = multivariantPlaylist.variants.getOrNull(variant.streamIndex)?.format ?: return null
    // A variant declares the combined bitrate of its video and associated audio renditions.
    val bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.peakBitrate.takeIf { it > 0 } ?: return null
    val durationUs = mediaPlaylist.durationUs.takeIf { it > 0 } ?: return null
    return (durationUs.toDouble() * bitrate / 8_000_000).toLong().takeIf { it > 0 }
}
