package com.aiyifan.app.core.startup

import com.aiyifan.app.core.data.remote.HttpFetcher
import com.aiyifan.app.core.data.remote.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The deadline must not wait for a blocking socket read to return. */
class StartupHttpFetcher : HttpFetcher {
    override suspend fun get(url: String): HttpResponse = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation {
            Dispatchers.IO.dispatch(EmptyCoroutineContext) { activeConnection.get()?.disconnect() }
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            var connection: HttpURLConnection? = null
            try {
                if (continuation.isActive) {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache")
                        setRequestProperty("Accept", "text/plain")
                    }
                    activeConnection.set(connection)
                    if (continuation.isActive) {
                        val code = connection.responseCode
                        // The config is one character; reject large/HTML/error responses.
                        val body = if (code in 200..299) connection.inputStream.use { input ->
                            val bytes = ByteArray(64)
                            var count = 0
                            while (count < bytes.size) {
                                val read = input.read(bytes, count, bytes.size - count)
                                if (read < 0) break
                                count += read
                            }
                            if (count == bytes.size) "" else String(bytes, 0, count, Charsets.UTF_8)
                        } else ""
                        continuation.resume(HttpResponse(code, body))
                    }
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection?.disconnect()
            }
        }
    }
}
