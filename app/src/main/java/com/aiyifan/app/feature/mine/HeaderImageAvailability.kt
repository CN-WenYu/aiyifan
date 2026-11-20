package com.aiyifan.app.feature.mine

internal class HeaderImageAvailability(
    private var online: Boolean,
    retryAt: Long? = null,
) {
    var retryAt = retryAt.takeIf { online }
        private set
    private var consecutiveFailures = 0
    val useRemote get() = online && retryAt == null

    fun setOnline(available: Boolean) {
        if (online == available) return
        online = available
        retryOnEntry()
    }

    fun retryOnEntry() {
        retryAt = null
        consecutiveFailures = 0
    }

    fun beforeAdvance(now: Long) {
        if (retryAt?.let { now >= it } == true) retryOnEntry()
    }

    fun remoteLoaded() {
        consecutiveFailures = 0
    }

    fun remoteFailed(now: Long) {
        if (useRemote && ++consecutiveFailures >= 2) retryAt = now + RETRY_DELAY_MS
    }

    private companion object {
        const val RETRY_DELAY_MS = 60_000L
    }
}
