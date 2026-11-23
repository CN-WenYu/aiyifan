package com.aiyifan.app.core.data.anime

import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.model.VideoSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

internal const val ANIME_KEY_PREFIX = "bangumi:"
internal fun String.isAnimeKey(): Boolean = startsWith(ANIME_KEY_PREFIX)

internal class AnimeSourceUnavailable : IOException("No matching anime stream")

internal class AnimeCatalog(private val bangumi: BangumiClient, private val source: XifanSource) {
    private val titles = object : LinkedHashMap<String, BangumiTitle>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BangumiTitle>?): Boolean =
            size > 32
    }

    suspend fun search(keyword: String): List<VideoSummary> = bangumi.search(keyword.trim())

    suspend fun detail(mediaKey: String, refresh: Boolean = false): VideoDetail =
        title(mediaKey, refresh).detail

    suspend fun resolvePlayback(detail: VideoDetail, episode: Episode): Episode {
        val title = title(detail.mediaKey)
        val number = title.episodeNumbers[episode.episodeKey] ?: throw AnimeSourceUnavailable()
        val url = source.play(title.names, number) ?: throw AnimeSourceUnavailable()
        return episode.copy(mediaUrl = url)
    }

    private suspend fun title(key: String, refresh: Boolean = false): BangumiTitle {
        if (!refresh) synchronized(titles) { titles[key] }?.let { return it }
        val id = key.takeIf(String::isAnimeKey)?.removePrefix(ANIME_KEY_PREFIX)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: throw AnimeHttpException(404)
        return bangumi.title(id).also { title ->
            synchronized(titles) { titles[key] = title }
        }
    }
}

internal data class AnimeSubjectNames(val chinese: String?, val original: String?, val year: String?) {
    val queries: List<String> get() = listOfNotNull(chinese, original).distinctBy(String::normalizedTitle)
}

internal data class SourceTitle(val id: Int, val title: String?, val original: String?, val year: String?)
internal data class SourceEpisode(val id: Int, val number: String?, val kind: String?)
internal data class BangumiTitle(
    val names: AnimeSubjectNames,
    val detail: VideoDetail,
    val episodeNumbers: Map<String, String>,
)

internal fun SourceTitle.hasMatchingName(subject: AnimeSubjectNames): Boolean {
    val names = subject.queries.map(String::normalizedTitle)
    return listOfNotNull(title, original).any { it.normalizedTitle() in names }
}

internal fun matchSourceTitle(subject: AnimeSubjectNames, candidates: List<SourceTitle>): SourceTitle? {
    val year = subject.year ?: return null
    return candidates.distinctBy(SourceTitle::id).singleOrNull { it.year == year && it.hasMatchingName(subject) }
}

private val wordSpace = Regex("[\\p{Z}\\s]+")
private fun String.normalizedTitle(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).replace(wordSpace, " ").trim().lowercase(Locale.ROOT)

private fun String?.episodeNumber(): BigDecimal? = this?.toBigDecimalOrNull()
    ?.takeIf { it > BigDecimal.ZERO }?.stripTrailingZeros()

internal fun matchSourceEpisode(number: String, candidates: List<SourceEpisode>): SourceEpisode? {
    val wanted = number.episodeNumber() ?: return null
    return candidates.singleOrNull { (it.kind == "main" || it.kind == "movie") && it.number.episodeNumber() == wanted }
}

internal class BangumiClient(private val transport: AnimeTransport) {
    private val headers = mapOf("User-Agent" to "Aiyifan/1.0 (Android; Bangumi catalog)")

    suspend fun search(keyword: String): List<VideoSummary> {
        if (keyword.isBlank()) return emptyList()
        val payload = JSONObject().put("keyword", keyword)
            .put("filter", JSONObject().put("type", JSONArray().put(ANIMATION_TYPE)))
        return request("/search/subjects?limit=30&offset=0", payload).getJSONArray("data").objects()
            .filter { it.optInt("type") == ANIMATION_TYPE }
            .mapNotNull { item ->
                val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                val name = item.text("name_cn") ?: item.text("name") ?: return@mapNotNull null
                VideoSummary(
                    mediaKey = key(id),
                    title = name,
                    coverUrl = cover(item),
                    videoType = ANIMATION_TYPE,
                    year = year(item.text("date")),
                    score = item.optJSONObject("rating")?.text("score"),
                )
            }.distinctBy(VideoSummary::mediaKey)
    }

    suspend fun title(id: Int): BangumiTitle {
        val subject = request("/subjects/$id")
        if (subject.optInt("type") != ANIMATION_TYPE || subject.optInt("id") != id) throw AnimeHttpException(404)
        val names = AnimeSubjectNames(subject.text("name_cn"), subject.text("name"), year(subject.text("date")))
        val episodes = episodes(id).mapNotNull { row ->
            val episodeId = row.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val number = row.text("ep").episodeNumber() ?: return@mapNotNull null
            val label = number.toPlainString()
            val name = row.text("name_cn") ?: row.text("name")
            Episode(key(episodeId), listOfNotNull(label, name).joinToString(" · "), episodeId, null) to label
        }.distinctBy { it.first.episodeKey }.sortedBy { it.second.toBigDecimal() }

        return BangumiTitle(
            names,
            VideoDetail(
                mediaKey = key(id),
                title = names.chinese ?: names.original ?: throw IOException("Missing Bangumi title"),
                coverUrl = cover(subject),
                videoType = ANIMATION_TYPE,
                introduce = subject.text("summary"),
                publishTime = subject.text("date"),
                commentEnabled = false,
                episodes = episodes.map { it.first },
            ),
            episodes.groupBy { it.second }.values.mapNotNull { it.singleOrNull() }
                .associate { (episode, number) -> episode.episodeKey to number },
        )
    }

    private suspend fun episodes(id: Int): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<Int>()
        var offset = 0
        do {
            val page = request("/episodes?subject_id=$id&type=0&limit=100&offset=$offset")
            val items = page.getJSONArray("data")
            val unique = items.objects().filter { seenIds.add(it.optInt("id")) }
            if (items.length() == 0 || unique.isEmpty()) break
            rows += unique.filter { it.optInt("type", -1) == 0 }
            offset += items.length()
        } while (offset < page.optInt("total", offset))
        return rows
    }

    private suspend fun request(path: String, body: JSONObject? = null): JSONObject =
        JSONObject(transport.request("https://api.bgm.tv/v0$path", body, headers))

    private fun key(id: Int) = "$ANIME_KEY_PREFIX$id"
    private fun cover(item: JSONObject) = item.optJSONObject("images")?.let {
        it.text("large") ?: it.text("common") ?: it.text("medium")
    }.orEmpty()

    private companion object {
        const val ANIMATION_TYPE = 2
    }
}

internal fun year(value: String?): String? =
    value?.take(4)?.takeIf { it.length == 4 && it.all { digit -> digit in '0'..'9' } && it != "0000" }
