package com.aiyifan.app.core.data.anime

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal class XifanSource(
    private val transport: AnimeTransport,
    private val requestIntervalMs: Long = 1_000,
) {
    private val requestLock = Mutex()
    private var lastRequest: Long? = null
    private var headers: Map<String, String>? = null
    private var headersUpdatedAt = 0L

    suspend fun play(subject: AnimeSubjectNames, number: String): String? {
        if (subject.year == null || subject.queries.isEmpty()) return null
        val matches = linkedMapOf<Int, Pair<SourceTitle, JSONObject>>()
        val seen = mutableSetOf<Int>()
        for (query in subject.queries) {
            val rows = search(query) ?: return null
            for (row in rows) {
                val hit = row.toSourceTitle() ?: continue
                if (!hit.hasMatchingName(subject) || !seen.add(hit.id)) continue
                if (hit.year != null && hit.year != subject.year) continue
                val detail = detail(hit.id)
                val item = detail.getJSONObject("anime").toSourceTitle() ?: continue
                if (item.id == hit.id && item.year == subject.year && item.hasMatchingName(subject)) {
                    matches[item.id] = item to detail
                    if (matches.size > 1) return null
                }
            }
        }
        val selected = matchSourceTitle(subject, matches.values.map { it.first }) ?: return null
        val lines = matches.getValue(selected.id).second.getJSONArray("sources").objects()
        var lineError: Exception? = null
        for (line in lines) {
            val name = line.text("name") ?: continue
            val episodes = line.getJSONArray("episodes").objects().mapNotNull { item ->
                val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                SourceEpisode(id, item.text("episode_number"), item.text("kind"))
            }
            val episode = matchSourceEpisode(number, episodes) ?: continue
            try {
                stream(episode.id, name)?.let { return it }
            } catch (error: AnimeHttpException) {
                // Authentication and throttling apply to the provider, not a single line.
                if (error.status !in 500..599 && error.status != 404 && error.status != 410) throw error
                lineError = error
            } catch (error: JSONException) {
                lineError = error
            }
        }
        lineError?.let { throw it }
        return null
    }

    private suspend fun search(query: String): List<JSONObject>? {
        val results = mutableListOf<JSONObject>()
        var page = 1
        do {
            val body = JSONObject()
                .put("search_term", query).put("page_number", page++).put("items_per_page", PAGE_SIZE)
                .put("sort_by", "bangumi_score").put("sort_order", "desc").put("filter_only_published", true)
            val rows = JSONArray(post("/rest/v1/rpc/search_animes", body))
            results += rows.objects()
            if (rows.length() < PAGE_SIZE) return results
        } while (page <= MAX_SEARCH_PAGES)
        // An incomplete result cannot rule out duplicate matches.
        return null
    }

    private suspend fun detail(id: Int): JSONObject =
        JSONObject(post("/rest/v1/rpc/get_anime_detail", JSONObject().put("p_id", id)))

    private suspend fun stream(id: Int, line: String): String? {
        val body = JSONObject().put("action", "fallback").put("episode_id", id)
        return JSONObject(post("/functions/v1/issue-web-playback", body))
            .getJSONArray("candidates").objects().firstNotNullOfOrNull { candidate ->
                candidate.text("url")?.takeIf { candidate.text("source_name") == line && isPlayableUrl(it) }
            }
    }

    private suspend fun post(path: String, body: JSONObject): String = requestLock.withLock {
        val now = System.nanoTime()
        val current = headers?.takeIf { elapsedMs(headersUpdatedAt, now) < HEADER_TTL_MS } ?: readHeaders()
        val last = lastRequest
        if (last != null) delay((requestIntervalMs - elapsedMs(last, System.nanoTime())).coerceAtLeast(0L))
        lastRequest = System.nanoTime()
        try {
            transport.request("$API$path", body, current)
        } catch (error: AnimeHttpException) {
            if (error.status == 401 || error.status == 403) headers = null
            throw error
        }
    }

    private suspend fun readHeaders(): Map<String, String> {
        val source = JSONObject(transport.request(SUBSCRIPTION, null, emptyMap()))
            .getJSONArray("mediaSources").objects().singleOrNull {
                it.text("id") == "xifan-dongman-next" && it.text("factoryId") == "json-api"
            } ?: throw IOException("Anime source configuration is unavailable")
        val published = source.getJSONObject("arguments").getJSONObject("searchConfig")
            .getString("headers").lineSequence().mapNotNull { line ->
                val parts = line.split(':', limit = 2)
                if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
            }.toMap()
        val apiKey = published["apikey"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("Anime source request configuration has changed")
        return mapOf("apikey" to apiKey, "Authorization" to "Bearer $apiKey", "Origin" to "https://next.xifanacg.com")
            .also {
                headers = it
                headersUpdatedAt = System.nanoTime()
            }
    }

    private fun JSONObject.toSourceTitle(): SourceTitle? =
        optInt("id").takeIf { it > 0 }?.let { id ->
            SourceTitle(id, text("title"), text("title_original"), year(text("release_date") ?: text("release_year")))
        }

    private fun elapsedMs(start: Long, end: Long) = TimeUnit.NANOSECONDS.toMillis(end - start)

    private fun isPlayableUrl(value: String): Boolean {
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return false }
        val path = uri.path.orEmpty()
        return uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            (path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".mp4", ignoreCase = true))
    }

    private companion object {
        const val SUBSCRIPTION = "https://sub.creamycake.org/v1/css1.json"
        const val API = "https://api.xifanacg.com"
        const val HEADER_TTL_MS = 30 * 60_000L
        const val PAGE_SIZE = 50
        const val MAX_SEARCH_PAGES = 4
    }
}
