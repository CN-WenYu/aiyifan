package com.aiyifan.app.feature.download

import android.app.job.JobScheduler
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.download.DownloadNetworkPolicy
import com.aiyifan.app.core.download.OfflineDownloadRepository
import com.aiyifan.app.core.download.STOP_REASON_WIFI
import com.aiyifan.app.core.download.WifiDownloadJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class WifiRecoveryDeviceTest {
    // Seed on cellular with a loopback fixture served via adb reverse. End the process,
    // connect Wi-Fi, then verify the persisted index before initializing the app's manager.
    @Test fun waitingDownloadSurvivesProcessDeath() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("wifiRecoveryPhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val waiting = "wifi-recovery-fixture"
        val paused = "wifi-recovery-paused-fixture"
        if (phase == "seed") {
            val policy = DownloadNetworkPolicy(context)
            assertEquals(STOP_REASON_WIFI, policy.stopReason(waiting))
            val uri = Uri.parse(requireNotNull(args.getString("fixtureUrl")))
            val repository = withContext(Dispatchers.Main) {
                AppGraph.downloadRepository.also { repo ->
                    repo.awaitLoaded()
                    repo.manager.addDownload(DownloadRequest.Builder(paused, uri).build(), OfflineDownloadRepository.STOP_REASON_USER)
                    repo.manager.addDownload(DownloadRequest.Builder(waiting, uri).build(), STOP_REASON_WIFI)
                }
            }
            withTimeout(5000) {
                while (repository.get(waiting)?.download?.stopReason != STOP_REASON_WIFI ||
                    context.getSystemService(JobScheduler::class.java).getPendingJob(WifiDownloadJob.JOB_ID) == null) delay(50)
            }
        } else {
            val database = androidx.media3.database.StandaloneDatabaseProvider(context)
            try {
                val index = androidx.media3.exoplayer.offline.DefaultDownloadIndex(database)
                assertEquals(Download.STATE_COMPLETED, index.getDownload(waiting)?.state)
                assertTrue(requireNotNull(index.getDownload(waiting)).bytesDownloaded > 0)
                assertEquals(OfflineDownloadRepository.STOP_REASON_USER, index.getDownload(paused)?.stopReason)
            } finally {
                database.close()
                withContext(Dispatchers.Main) {
                    AppGraph.downloadRepository.remove(waiting)
                    AppGraph.downloadRepository.remove(paused)
                }
            }
        }
    }
}
