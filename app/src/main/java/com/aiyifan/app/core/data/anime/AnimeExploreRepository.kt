package com.aiyifan.app.core.data.anime

import org.json.JSONObject
import java.net.URLEncoder

internal data class AnimeExploreSubject(
    val mediaKey: String,
    val title: String,
    val coverUrl: String?,
    val metadata: String? = null,
    val rank: Int? = null,
)

internal data class AnimePage<T>(val items: List<T>, val nextOffset: Int?)

internal data class AnimeAiring(
    val episodeId: Int,
    val subject: AnimeExploreSubject,
    val episodeNumber: String?,
    val episodeTitle: String?,
    val airingTime: String?,
)

internal data class AnimeScheduleDay(val date: String, val items: List<AnimeAiring>)

internal class AnimeExploreRepository(private val transport: AnimeTransport) {
    suspend fun trending(): List<AnimeExploreSubject> =
        request("/v1/trends").getJSONArray("trendingSubjects").objects()
            .mapNotNull { row -> row.subject("bangumiId", "nameCn", "name", "imageLarge") }
            .distinctBy(AnimeExploreSubject::mediaKey)
            .take(10)

    suspend fun recommendations(offset: Int): AnimePage<AnimeExploreSubject> =
        page("/v2/home/recommendations", offset) { row ->
            row.subject("subjectId", "subjectNameCn", "subjectName", "imageUrl")?.copy(
                metadata = listOfNotNull(row.text("desc1"), row.text("desc2"))
                    .joinToString(" · ").ifEmpty { null },
            )
        }

    suspend fun ranking(offset: Int): AnimePage<AnimeExploreSubject> =
        page("/v2/trends/subjects", offset) { row ->
            val subject = row.optJSONObject("subject") ?: return@page null
            subject.subject("id", "nameCn", "name", "imageLarge")?.copy(
                metadata = listOfNotNull(subject.text("airDate"), subject.text("score"))
                    .joinToString(" · ").ifEmpty { null },
                rank = row.positiveId("trendingRank"),
            )
        }

    suspend fun schedule(today: String, timeZone: String): List<AnimeScheduleDay> =
        request("/v1/schedule/airing?today=${encode(today)}&timeZone=${encode(timeZone)}")
            .getJSONArray("list").objects().mapNotNull { day ->
                val date = day.text("date")?.takeIf(DATE::matches) ?: return@mapNotNull null
                val rows = day.optJSONArray("list") ?: return@mapNotNull null
                AnimeScheduleDay(date, rows.objects().mapNotNull(::airing).distinctBy(AnimeAiring::episodeId))
            }.distinctBy(AnimeScheduleDay::date).sortedBy(AnimeScheduleDay::date)

    private fun airing(row: JSONObject): AnimeAiring? {
        val subject = row.optJSONObject("subject")
            ?.subject("subjectId", "nameCn", "name", "imageLarge") ?: return null
        val episode = row.optJSONObject("episode") ?: return null
        return AnimeAiring(
            episodeId = episode.positiveId("episodeId") ?: return null,
            subject = subject,
            episodeNumber = episode.text("ep") ?: episode.text("sort"),
            episodeTitle = episode.text("nameCn") ?: episode.text("name"),
            airingTime = row.text("airingTime").takeIf { row.optBoolean("timeKnown") },
        )
    }

    private suspend fun page(
        path: String,
        offset: Int,
        parse: (JSONObject) -> AnimeExploreSubject?,
    ): AnimePage<AnimeExploreSubject> {
        val response = request("$path?limit=$PAGE_SIZE&offset=$offset")
        val rows = response.getJSONArray("items")
        val next = offset + rows.length()
        val hasMore = rows.length() > 0 &&
            (if (response.has("total")) next < response.getInt("total") else rows.length() >= PAGE_SIZE)
        val items = rows.objects().mapNotNull(parse).distinctBy(AnimeExploreSubject::mediaKey)
        return AnimePage(items, next.takeIf { hasMore })
    }

    private suspend fun request(path: String): JSONObject = JSONObject(
        transport.request("$API_BASE$path", null, mapOf("User-Agent" to "Aiyifan/1.0 (Android; Anime exploration)")),
    )

    private fun JSONObject.subject(idKey: String, chinese: String, original: String, cover: String): AnimeExploreSubject? {
        val id = positiveId(idKey) ?: return null
        val title = text(chinese) ?: text(original) ?: return null
        return AnimeExploreSubject("$ANIME_KEY_PREFIX$id", title, text(cover)?.takeIf { it.startsWith("https://") })
    }

    private fun JSONObject.positiveId(key: String): Int? = optInt(key).takeIf { it > 0 }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val API_BASE = "https://api.animeko.org"
        const val PAGE_SIZE = 24
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
