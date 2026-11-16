@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.aiyifan.app.R
import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.feature.download.VideoDownloadService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val mutations = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutation(id: String) = mutations.getOrPut(id) { Mutex() }
    private val refreshLock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()
    val mobileDataAllowed: Boolean get() = preferences.getBoolean("mobile_data_allowed", false)

    init {
        checkMainThread()
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
        manager.requirements = requirements()
        refresh()
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) refreshNow()
            }
        }
    }

    fun setMobileDataAllowed(allowed: Boolean) {
        checkMainThread()
        preferences.edit().putBoolean("mobile_data_allowed", allowed).apply()
        manager.requirements = requirements()
        refresh()
    }

    suspend fun enqueue(detail: VideoDetail, episode: Episode): String = mutation(downloadId(detail.mediaKey, episode.episodeKey)).withLock {
        withContext(Dispatchers.Main.immediate) {
            initialized.await()
            val id = downloadId(detail.mediaKey, episode.episodeKey)
            if (readDownload(id) != null) return@withContext id
            awaitPermittedNetwork()
            val resolved = catalog.resolveDownload(detail, episode)
            val metadata = DownloadMetadata(detail, resolved, detail.episodes.indexOfFirst { it.episodeKey == episode.episodeKey }.coerceAtLeast(0))
            val request = prepareRequest(id, metadata)
            withContext(NonCancellable) {
                startService()
                manager.addDownload(request)
                manager.resumeDownloads()
                awaitDownload(id) { it != null }
                refreshNow()
            }
            id
        }
    }

    fun pause(id: String) {
        checkMainThread()
        manager.setStopReason(id, STOP_REASON_USER)
        refresh()
    }

    fun resume(id: String) {
        checkMainThread()
        startService()
        manager.setStopReason(id, Download.STOP_REASON_NONE)
        manager.resumeDownloads()
        refresh()
    }

    suspend fun retry(id: String) = mutation(id).withLock {
        withContext(Dispatchers.Main.immediate) {
            initialized.await()
            val previous = readDownload(id) ?: return@withContext
            if (previous.state == Download.STATE_COMPLETED) return@withContext
            val metadata = DownloadMetadata.decode(previous.request.data)
                ?: throw IOException(context.getString(R.string.download_error_metadata))
            if (requirements().getNotMetRequirements(context) != 0) throw DownloadNetworkUnavailableException()
            // Strict resolution must succeed before touching the old request or any cached bytes.
            val resolved = catalog.resolveDownload(metadata.detail, metadata.episode)
            val updated = metadata.copy(episode = resolved)
            val changed = requiresDownloadReplacement(previous.request.uri.toString(), requireNotNull(resolved.mediaUrl))
            val request = if (changed) prepareRequest(id, updated) else DownloadRequest.Builder(id, previous.request.uri)
                .setMimeType(previous.request.mimeType)
                .setStreamKeys(previous.request.streamKeys)
                .setCustomCacheKey(previous.request.customCacheKey)
                .setData(updated.encode()).build()
            withContext(NonCancellable) {
                startService()
                if (changed) removeAndAwait(id)
                manager.addDownload(request, Download.STOP_REASON_NONE)
                manager.resumeDownloads()
                awaitDownload(id) { it != null && it.state != Download.STATE_REMOVING }
                refreshNow()
            }
        }
    }

    suspend fun remove(id: String) = mutation(id).withLock {
        // Once requested, removal continues to a persisted Media3 removal even if a screen closes.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            initialized.await()
            if (readDownload(id) == null) return@withContext
            startService()
            removeAndAwait(id)
            refreshNow()
        }
    }

    suspend fun awaitLoaded() = withContext(Dispatchers.Main.immediate) { refreshNow() }

    fun get(id: String): DownloadTask? = tasks.value.firstOrNull { it.id == id }

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

    private suspend fun awaitPermittedNetwork() {
        // Preparation can read keys and probe segments too, so it follows the download policy.
        while (requirements().getNotMetRequirements(context) != 0) delay(500)
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
        mutableTasks.value = persisted.map { active[it.request.id] ?: it }
            .map { download -> DownloadTask(download, DownloadMetadata.decode(download.request.data), failure =
                failureNotes.getString(download.request.id, null)?.let { name -> DownloadFailure.entries.firstOrNull { it.name == name } }) }
            .sortedByDescending { it.download.startTimeMs }
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
        }
    }

    private fun requirements(): Requirements = Requirements(
        if (mobileDataAllowed) Requirements.NETWORK else Requirements.NETWORK_UNMETERED,
    )

    private fun startService() {
        DownloadService.startForeground(context, VideoDownloadService::class.java)
    }

    private suspend fun prepareRequest(id: String, metadata: DownloadMetadata): DownloadRequest {
        val uri = Uri.parse(metadata.episode.mediaUrl)
        require(uri.scheme == "https" || uri.scheme == "http") { context.getString(R.string.download_error_source) }
        val type = Util.inferContentType(uri)
        require(type == C.CONTENT_TYPE_HLS || type == C.CONTENT_TYPE_OTHER) { context.getString(R.string.download_error_format) }
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
            DefaultMediaSourceFactory(runtime.upstream)
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
                                require(manifest == null || manifest.mediaPlaylist.hasEndTag) { context.getString(R.string.download_error_live) }
                                require(manifest == null || (manifest.mediaPlaylist.protectionSchemes == null && manifest.multivariantPlaylist.sessionKeyDrmInitData.isEmpty())) {
                                    context.getString(R.string.download_error_drm)
                                }
                                var hasSelectedAudioVideo = false
                                for (period in 0 until helper.periodCount) {
                                    val groups = helper.getTrackGroups(period)
                                    for (group in 0 until groups.length) {
                                        for (track in 0 until groups[group].length) {
                                            require(groups[group].getFormat(track).drmInitData == null) { context.getString(R.string.download_error_drm) }
                                        }
                                    }
                                    val mapped = helper.getMappedTrackInfo(period)
                                    for (renderer in 0 until mapped.rendererCount) {
                                        if (mapped.getRendererType(renderer) == C.TRACK_TYPE_VIDEO || mapped.getRendererType(renderer) == C.TRACK_TYPE_AUDIO) {
                                            if (helper.getTrackSelections(period, renderer).isNotEmpty()) hasSelectedAudioVideo = true
                                        }
                                    }
                                }
                                require(hasSelectedAudioVideo) { context.getString(R.string.download_error_tracks) }
                                val request = helper.getDownloadRequest(id, metadata.encode())
                                // Empty HLS stream keys mean "download all", so reject instead of silently downloading every variant.
                                require(type != C.CONTENT_TYPE_HLS || request.streamKeys.isNotEmpty()) { context.getString(R.string.download_error_tracks) }
                                continuation.resume(request)
                            } catch (error: Exception) {
                                continuation.resumeWithException(error)
                            }
                        }
                        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(
                                IOException(context.getString(if (e is DownloadHelper.LiveContentUnsupportedException) R.string.download_error_live else R.string.download_error_prepare)),
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

    companion object {
        const val STOP_REASON_USER = 1
        const val STOP_REASON_TIMEOUT = 2
    }
}

/** Exactly one cache and manager per application process, also if a second repository is requested. */
private class DownloadRuntime private constructor(context: Context) {
    val upstream = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline_downloads"), NoOpCacheEvictor(), database)
    private val executor = Executors.newFixedThreadPool(2)
    val manager = DownloadManager(context, DefaultDownloadIndex(database), DownloaderFactory { request ->
        DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(taskCacheKeys(request.id)),
            executor,
        ).createDownloader(request)
    }).apply {
        maxParallelDownloads = 2
        minRetryCount = 2
    }

    companion object {
        private var instance: DownloadRuntime? = null
        @Synchronized fun get(context: Context): DownloadRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: DownloadRuntime(context).also { instance = it }
        }
    }
}

private fun taskCacheKeys(id: String) = CacheKeyFactory { spec ->
    "${taskCachePrefix(id)}${CacheKeyFactory.DEFAULT.buildCacheKey(spec)}"
}

private fun taskCachePrefix(id: String) = "${id.length}:$id:"
