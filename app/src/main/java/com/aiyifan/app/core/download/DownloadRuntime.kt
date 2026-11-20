@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.content.Context
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Mutex

/** Exactly one cache and manager per application process, also if a second repository is requested. */
internal class DownloadRuntime private constructor(context: Context) {
    val policy = DownloadNetworkPolicy(context)
    val upstream = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline_downloads"), NoOpCacheEvictor(), database)
    val snapshots = DownloadSnapshotStore(File(context.filesDir, "offline_download_metadata"))
    val mutations = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val filmMutations = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    fun filmMutation(mediaKey: String) = filmMutations.getOrPut(mediaKey) { Mutex() }
    var defaultPreparer: (suspend (DownloadRequest) -> DownloadRequest)? = null
    val preparers = java.util.concurrent.ConcurrentHashMap<String, suspend (DownloadRequest) -> DownloadRequest>()
    private val executor = Executors.newFixedThreadPool(3)
    val manager = DownloadManager(context, DefaultDownloadIndex(database), DownloaderFactory { request ->
        val factory = DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(policy.upstream(request.id, upstream))
                .setCacheKeyFactory(taskCacheKeys(request.id)),
            executor,
        )
        if (isDeferred(request)) DeferredDownloader(request, snapshots,
            { queued -> requireNotNull(preparers[queued.id] ?: defaultPreparer).invoke(queued) },
            factory::createDownloader,
            { cache.keys.filter { it.startsWith(taskCachePrefix(request.id)) }.forEach(cache::removeResource) })
        else factory.createDownloader(request)
    }).apply {
        maxParallelDownloads = policy.prefs.getInt("parallel_downloads", 2).coerceIn(1, 3)
        minRetryCount = 2
        policy.attach(this)
    }

    companion object {
        private var instance: DownloadRuntime? = null
        @Synchronized fun get(context: Context): DownloadRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: DownloadRuntime(context).also { instance = it }
        }
    }
}

internal fun taskCacheKeys(id: String) = CacheKeyFactory { spec ->
    "${taskCachePrefix(id)}${CacheKeyFactory.DEFAULT.buildCacheKey(spec)}"
}

internal fun taskCachePrefix(id: String) = "${id.length}:$id:"

internal fun isDeferred(request: DownloadRequest) = request.uri.scheme == "offline-task"
