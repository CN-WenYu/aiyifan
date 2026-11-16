@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** Runs inside a Media3 download slot, so constraints/cancellation cover preparation too. */
internal class DeferredDownloader(
    private val request: DownloadRequest,
    private val store: DownloadSnapshotStore,
    private val prepare: suspend (DownloadRequest) -> DownloadRequest,
    private val createDelegate: (DownloadRequest) -> Downloader,
    private val clearCache: () -> Unit,
) : Downloader {
    private val job = SupervisorJob()
    @Volatile private var delegate: Downloader? = null
    @Volatile private var canceled = false

    override fun download(progressListener: Downloader.ProgressListener?) {
        val prepared = runBlocking(job) {
            val generation = requireNotNull(DownloadMetadata.decode(request.data)).generation
            val previous = store.source(request.id)
            if (previous != null && previous.generation == generation) return@runBlocking previous.request
            val resolved = prepare(request)
            ensureActive()
            if (previous != null && requiresDownloadReplacement(previous.request.uri.toString(), resolved.uri.toString())) clearCache()
            ensureActive()
            store.saveSource(generation, resolved)
            resolved
        }
        if (canceled) throw InterruptedException()
        val downloader = createDelegate(prepared)
        delegate = downloader
        if (canceled) { downloader.cancel(); throw InterruptedException() }
        downloader.download(progressListener)
    }

    override fun cancel() {
        canceled = true
        job.cancel()
        delegate?.cancel()
    }

    override fun remove() {
        // Namespace cleanup needs no manifest, source URL or network connection.
        clearCache()
        store.removeSource(request.id)
    }
}
