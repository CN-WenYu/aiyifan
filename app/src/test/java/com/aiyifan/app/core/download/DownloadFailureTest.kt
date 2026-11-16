package com.aiyifan.app.core.download

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadFailureTest {
    @Test fun `disk full is detected through wrapped cache errors`() {
        assertEquals(DownloadFailure.NO_SPACE, DownloadFailure.from(IOException("cache", IOException("open failed: ENOSPC (No space left on device)"))))
    }

    @Test fun `expired and missing resources have distinct actionable errors`() {
        fun response(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "http", IOException(), emptyMap(), DataSpec.Builder().setUri("https://invalid/test").build(), byteArrayOf())
        assertEquals(DownloadFailure.EXPIRED, DownloadFailure.from(response(403)))
        assertEquals(DownloadFailure.EXPIRED, DownloadFailure.from(DownloadPreparationException(response(403))))
        assertEquals(DownloadFailure.NOT_FOUND, DownloadFailure.from(response(404)))
        assertEquals(DownloadFailure.NETWORK, DownloadFailure.from(IOException("offline")))
    }

    @Test fun `unsupported sources retain specific preparation failures through wrappers`() {
        assertEquals(DownloadFailure.LIVE, DownloadFailure.from(IOException(DownloadPreparationException(IOException(), DownloadFailure.LIVE))))
        assertEquals(DownloadFailure.DRM, DownloadFailure.from(DownloadPreparationException(IOException(), DownloadFailure.DRM)))
        assertEquals(DownloadFailure.PREPARATION, DownloadFailure.from(DownloadPreparationException(IOException())))
    }
}
