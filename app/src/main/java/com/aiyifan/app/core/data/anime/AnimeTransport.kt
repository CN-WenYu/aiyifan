package com.aiyifan.app.core.data.anime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal fun interface AnimeTransport {
    suspend fun request(url: String, body: JSONObject?, headers: Map<String, String>): String
}

internal class AnimeHttpException(val status: Int) : IOException("Anime request returned HTTP $status")

internal class HttpsAnimeTransport : AnimeTransport {
    override suspend fun request(url: String, body: JSONObject?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw AnimeHttpException(status)
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
}

internal fun JSONObject.text(key: String): String? =
    opt(key)?.takeUnless { it === JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
