@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.feature.download.VideoDownloadService

/** Own the resumed transfer for the lifetime of the job, including on Android 12+ where a
 * background network change cannot launch a foreground service. Media3 still owns the queue. */
class WifiDownloadJob : JobService(), DownloadManager.Listener {
    private var params: JobParameters? = null
    private val manager get() = AppGraph.downloadRepository.manager

    override fun onStartJob(params: JobParameters): Boolean {
        this.params = params
        manager.addListener(this)
        AppGraph.downloadRepository.reconcileNetwork()
        manager.resumeDownloads()
        if (manager.isInitialized && manager.isIdle) onIdle(manager)
        return true
    }

    override fun onIdle(downloadManager: DownloadManager) {
        val running = params ?: return
        detach()
        // On older Android versions JobScheduler cannot filter by transport. Also recheck
        // the default network because the network matching the job may be secondary.
        jobFinished(running, downloadManager.currentDownloads.any { it.needsNetworkRecovery })
    }

    override fun onStopJob(params: JobParameters): Boolean {
        detach()
        if (!VideoDownloadService.isRunning) manager.pauseDownloads()
        return !manager.isInitialized || manager.currentDownloads.any { it.needsNetworkRecovery }
    }

    private fun detach() {
        manager.removeListener(this)
        params = null
    }

    companion object {
        internal const val JOB_ID = 7303

        internal fun update(context: Context, downloads: List<Download>) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing != null) {
                if (downloads.none { it.needsNetworkRecovery }) scheduler.cancel(JOB_ID)
                return
            }
            if (downloads.none { it.stopReason == STOP_REASON_WIFI && it.state == Download.STATE_STOPPED }) return

            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WifiDownloadJob::class.java))
                .setPersisted(true)
            if (Build.VERSION.SDK_INT >= 28) {
                job.setRequiredNetwork(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build())
            } else {
                job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            }
            if (scheduler.schedule(job.build()) != JobScheduler.RESULT_SUCCESS) {
                Log.w("WifiDownloadJob", "System declined to schedule Wi-Fi download recovery")
            }
        }
    }
}

private val Download.needsNetworkRecovery: Boolean
    get() = state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING ||
        (state == Download.STATE_STOPPED && stopReason == STOP_REASON_WIFI)
