@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aiyifan.app.core.download

import androidx.media3.datasource.HttpDataSource
import com.aiyifan.app.R

class DownloadNetworkUnavailableException : java.io.IOException("Download network requirements are unmet")
class DownloadPreparationException(cause: Throwable, val failure: DownloadFailure = DownloadFailure.PREPARATION) :
    java.io.IOException("Unable to prepare offline source", cause)

enum class DownloadFailure(val messageRes: Int) {
    NO_SPACE(R.string.download_error_space),
    EXPIRED(R.string.download_error_expired),
    NOT_FOUND(R.string.download_error_not_found),
    SOURCE(R.string.download_error_source),
    FORMAT(R.string.download_error_format),
    LIVE(R.string.download_error_live),
    DRM(R.string.download_error_drm),
    TRACKS(R.string.download_error_tracks),
    PREPARATION(R.string.download_error_prepare),
    NETWORK(R.string.download_error_network);

    companion object {
        fun from(error: Throwable): DownloadFailure {
            var fallback = NETWORK
            var cause: Throwable? = error
            repeat(16) {
                val current = cause ?: return fallback
                if (current is DownloadPreparationException) fallback = current.failure
                val message = current.message.orEmpty()
                if (message.contains("ENOSPC", true) || message.contains("No space left", true)) return NO_SPACE
                if (current is HttpDataSource.InvalidResponseCodeException) {
                    when (current.responseCode) {
                        401, 403 -> return EXPIRED
                        404, 410 -> return NOT_FOUND
                    }
                }
                cause = current.cause
            }
            return fallback
        }
    }
}
