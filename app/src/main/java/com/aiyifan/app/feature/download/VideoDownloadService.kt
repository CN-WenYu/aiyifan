@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.feature.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph

class VideoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1_000L,
    CHANNEL_ID,
    R.string.download_notification_channel,
    0,
) {
    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun getDownloadManager(): DownloadManager = AppGraph.downloadRepository.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, SCHEDULER_JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val intent = Intent(this, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            pendingIntent,
            getString(R.string.download_notification_message),
            downloads,
            notMetRequirements,
        )
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppGraph.downloadRepository.pauseForTimeout()
        PlatformScheduler(this, SCHEDULER_JOB_ID).cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal var isRunning = false
            private set
        const val CHANNEL_ID = "offline_downloads"
        const val NOTIFICATION_ID = 7301
        const val SCHEDULER_JOB_ID = 7302
    }
}
