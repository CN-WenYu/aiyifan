package com.aiyifan.app.core.data.anime

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeCatalogTest {
    @Test
    fun `search requests animated subjects and keeps Bangumi identity`() = runBlocking {
        val catalog = catalog { url, body, headers ->
            assertTrue(url.endsWith("/v0/search/subjects?limit=30&offset=0"))
            assertEquals("葬送的芙莉莲", body!!.getString("keyword"))
            assertEquals(2, body.getJSONObject("filter").getJSONArray("type").getInt(0))
            assertTrue(headers.getValue("User-Agent").startsWith("Aiyifan/"))
            """{"data":[{"id":14,"type":2,"name":"原名","name_cn":"中文","date":"2023-09-29"},
                         {"id":23,"type":1,"name":"同名书籍"}]}"""
        }
        val result = catalog.search("  葬送的芙莉莲  ").single()
        assertEquals("bangumi:14", result.mediaKey)
        assertEquals("中文", result.title)
        assertEquals("2023", result.year)
    }

    @Test
    fun `empty results are distinct from rate limiting`() = runBlocking {
        val empty = catalog { _, _, _ -> """{"data":[]}""" }
        assertTrue(empty.search("不存在").isEmpty())
        val limited = catalog { _, _, _ -> throw AnimeHttpException(429) }
        assertEquals(429, (runCatching { limited.search("葬送的芙莉莲") }
            .exceptionOrNull() as AnimeHttpException).status)
    }

    @Test
    fun `matching checks year and rejects ambiguous same season candidates`() {
        val subject = AnimeSubjectNames("作品 第二季", "作品 2nd Season", "2024")
        val wrongYear = SourceTitle(1, "作品 第二季", null, "2023")
        val wrongSeason = SourceTitle(2, "作品", null, "2024")
        val exact = SourceTitle(3, "作品　第二季", null, "2024")
        assertEquals(exact, matchSourceTitle(subject, listOf(wrongYear, wrongSeason, exact)))
        assertNull(matchSourceTitle(subject, listOf(exact, exact.copy(id = 4))))
    }

    @Test
    fun `split seasons use local decimal numbers and exclude specials and duplicates`() {
        val first = SourceEpisode(2, "1.00", "main")
        val decimal = SourceEpisode(3, "1.50", "main")
        val remapped = SourceEpisode(4, "13", "main")
        val special = SourceEpisode(5, "1.5", "sp")
        assertEquals(decimal, matchSourceEpisode("1.5", listOf(first, decimal, remapped, special)))
        assertNull(matchSourceEpisode("1.5", listOf(decimal, decimal.copy(id = 6))))
        assertNull(matchSourceEpisode("2", listOf(first, decimal)))
    }

    @Test
    fun `playback matches the line and obtains a fresh URL when retried`() = runBlocking {
        var playRequests = 0
        val catalog = catalog { url, body, _ ->
            when {
                url.endsWith("/v0/subjects/14") ->
                    """{"id":14,"type":2,"name":"Test","name_cn":"测试","date":"2024-01-01"}"""
                url.contains("/v0/episodes?") ->
                    """{"total":2,"data":[{"id":81,"type":0,"sort":13,"ep":1.5,"name_cn":"正片"},
                                          {"id":82,"type":1,"sort":1.5,"ep":1.5,"name_cn":"特典"}]}"""
                url.contains("css1.json") ->
                    """{"mediaSources":[{"id":"xifan-dongman-next","factoryId":"json-api","arguments":{"searchConfig":{"headers":"apikey: test\nAuthorization: Bearer test\nOrigin: https://next.xifanacg.com"}}}]}"""
                url.endsWith("/search_animes") -> """[{"id":23,"title":"测试","release_year":2024}]"""
                url.endsWith("/get_anime_detail") ->
                    """{"anime":{"id":23,"title":"测试","release_date":"2024-01-01"},
                        "sources":[{"name":"主线","episodes":[{"id":91,"kind":"main","episode_number":"1.50"}]}]}"""
                url.endsWith("/issue-web-playback") -> {
                    assertEquals(91, body!!.getInt("episode_id"))
                    playRequests++
                    val other = """{"source_name":"错误线路","url":"https://example.com/wrong.mp4"}"""
                    if (playRequests == 1) """{"candidates":[$other]}"""
                    else """{"candidates":[$other,{"source_name":"主线","url":"https://example.com/right.mp4?v=$playRequests"}]}"""
                }
                else -> error("Unexpected endpoint: $url")
            }
        }
        val detail = catalog.detail("bangumi:14")
        val episode = detail.episodes.single()
        assertEquals("bangumi:81", episode.episodeKey)
        assertTrue(runCatching { catalog.resolvePlayback(detail, episode) }.exceptionOrNull() is AnimeSourceUnavailable)
        assertEquals("https://example.com/right.mp4?v=2", catalog.resolvePlayback(detail, episode).mediaUrl)
        assertEquals("https://example.com/right.mp4?v=3", catalog.resolvePlayback(detail, episode).mediaUrl)
    }

    private fun catalog(handler: suspend (String, JSONObject?, Map<String, String>) -> String): AnimeCatalog {
        val transport = AnimeTransport { url, body, headers -> handler(url, body, headers) }
        return AnimeCatalog(BangumiClient(transport), XifanSource(transport, requestIntervalMs = 0))
    }
}
