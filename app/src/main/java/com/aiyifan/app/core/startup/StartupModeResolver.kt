package com.aiyifan.app.core.startup

import com.aiyifan.app.core.data.remote.HttpFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class AppMode { ONLINE, LOCAL }

class StartupModeResolver(private val fetcher: HttpFetcher, private val timeoutMs: Long = 5_000) {
    suspend fun resolve(): AppMode = try {
        withTimeoutOrNull(timeoutMs) {
            val response = fetcher.get(CONFIG_URL)
            if (response.code in 200..299 && response.body.trim() == "1") AppMode.ONLINE else AppMode.LOCAL
        } ?: AppMode.LOCAL
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AppMode.LOCAL
    }

    companion object {
        const val CONFIG_URL = "https://raw.githubusercontent.com/xiaodaitaishuai1/aiyifan/main/app/daidai.kt"
    }
}
