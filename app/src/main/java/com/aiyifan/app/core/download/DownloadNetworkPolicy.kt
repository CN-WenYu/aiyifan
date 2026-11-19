@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements

/** Only this stop reason may be released automatically when Wi-Fi becomes available. */
internal const val STOP_REASON_WIFI = 3

internal class DownloadNetworkPolicy(private val context: Context) {
    private val network = context.getSystemService(ConnectivityManager::class.java)
    val prefs = context.getSharedPreferences("offline_download_settings", Context.MODE_PRIVATE)
    val always get() = prefs.getBoolean("mobile_data_allowed", false)
    private fun caps() = network.getNetworkCapabilities(network.activeNetwork)
    private fun trustedTransport(cap: NetworkCapabilities?) = cap != null &&
        (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
        !cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    val needsConsent get() = caps()?.let { !trustedTransport(it) } == true
    fun authorized(id: String) = always || prefs.getBoolean("once:$id", false)
    fun stopReason(id: String): Int = if (authorized(id) || trustedTransport(caps())) Download.STOP_REASON_NONE else STOP_REASON_WIFI

    fun grantOnce(id: String, allowed: Boolean) {
        val edit = prefs.edit()
        if (allowed) edit.putBoolean("once:$id", true) else edit.remove("once:$id")
        edit.apply()
    }

    fun enforce(id: String) {
        if (stopReason(id) != Download.STOP_REASON_NONE) throw DownloadNetworkUnavailableException()
    }

    @Volatile private var revision = 0L

    fun upstream(id: String, source: DataSource.Factory) = DataSource.Factory {
        val delegate = source.createDataSource()
        object : DataSource by delegate {
            private var checkedRevision = -1L
            override fun open(dataSpec: DataSpec): Long {
                checkedRevision = revision
                enforce(id)
                return delegate.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val current = revision
                if (checkedRevision != current) {
                    enforce(id)
                    checkedRevision = current
                }
                return delegate.read(buffer, offset, length)
            }
        }
    }

    fun reconcile(manager: DownloadManager) {
        revision++
        manager.currentDownloads.forEach { task ->
            val reason = stopReason(task.request.id)
            if (task.stopReason != reason && (task.stopReason == STOP_REASON_WIFI ||
                    task.state == Download.STATE_QUEUED || task.state == Download.STATE_DOWNLOADING)) {
                manager.setStopReason(task.request.id, reason)
            }
        }
    }

    fun attach(manager: DownloadManager) {
        manager.requirements = Requirements(Requirements.NETWORK)
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                reconcile(downloadManager)
                WifiDownloadJob.update(context, downloadManager.currentDownloads)
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                WifiDownloadJob.update(context, downloadManager.currentDownloads)
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                WifiDownloadJob.update(context, downloadManager.currentDownloads)
                if (download.state == Download.STATE_COMPLETED) grantOnce(download.request.id, false)
                if (download.state in listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_STOPPED)) {
                    val reason = stopReason(download.request.id)
                    if (reason != download.stopReason && (download.stopReason == STOP_REASON_WIFI || download.stopReason == 0)) {
                        manager.setStopReason(download.request.id, reason)
                    }
                }
            }
        })
        val main = Handler(Looper.getMainLooper())
        val update = Runnable { reconcile(manager) }
        network.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                main.removeCallbacks(update)
                main.post(update)
            }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
        })
    }
}
