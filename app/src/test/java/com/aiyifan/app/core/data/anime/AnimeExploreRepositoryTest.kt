package com.aiyifan.app.core.data.anime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeExploreRepositoryTest {
    @Test
    fun `trending keeps upstream order and returns at most ten unique titles`() = runBlocking {
        val rows = (1..11).joinToString(",") { """{"bangumiId":$it,"nameCn":"番剧$it"}""" }
        val repository = repository("/v1/trends", """{"trendingSubjects":[null,{"bangumiId":1},$rows]}""")

        assertEquals((1..10).map { "bangumi:$it" }, repository.trending().map { it.mediaKey })
    }

    @Test
    fun `recommendation advances by raw page length even with duplicate and malformed rows`() = runBlocking {
        val repository = repository(
            "/v2/home/recommendations?limit=24&offset=24",
            """{"total":28,"items":[{"subjectId":32,"subjectName":"原名","desc2":"8.4 分"},
                {"subjectId":32,"subjectName":"重复"},null]}""",
        )
        val page = repository.recommendations(24)

        assertEquals(27, page.nextOffset)
        assertEquals("bangumi:32", page.items.single().mediaKey)
        assertEquals("原名", page.items.single().title)
        assertEquals("8.4 分", page.items.single().metadata)
    }

    @Test
    fun `ranking preserves rank and treats an empty page as terminal`() = runBlocking {
        val repository = repository(
            "/v2/trends/subjects?limit=24&offset=0",
            """{"total":1,"items":[{"trendingRank":1,"subject":{"id":9,"nameCn":"热门","score":"9.1"}}]}""",
        )
        val page = repository.ranking(0)

        assertEquals(1, page.items.single().rank)
        assertNull(page.nextOffset)
        val empty = repository("/v2/trends/subjects?limit=24&offset=24", """{"total":80,"items":[]}""")
        assertNull(empty.ranking(24).nextOffset)
    }

    @Test
    fun `airing keeps local dates and playable Bangumi subjects without inventing unknown times`() = runBlocking {
        val repository = repository(
            "/v1/schedule/airing?today=2026-11-23&timeZone=Asia%2FShanghai",
            """{"list":[{"date":"2026-11-24","list":[{"subject":{"subjectId":9,"name":"番剧"},
                "episode":{"episodeId":31,"type":0,"ep":"4","nameCn":"更新"},
                "airingTime":"2026-11-24T16:00:00Z","timeKnown":false}]},
                {"date":"2026-11-23","list":[]}]}""",
        )
        val days = repository.schedule("2026-11-23", "Asia/Shanghai")

        assertEquals(listOf("2026-11-23", "2026-11-24"), days.map { it.date })
        assertTrue(days.first().items.isEmpty())
        val episode = days.last().items.single()
        assertEquals("bangumi:9", episode.subject.mediaKey)
        assertEquals("4", episode.episodeNumber)
        assertNull(episode.airingTime)
    }

    @Test
    fun `missing feed schema is an error and a real empty feed is not`() = runBlocking {
        assertTrue(repository("/v1/trends", """{"trendingSubjects":[]}""").trending().isEmpty())
        assertTrue(runCatching { repository("/v1/trends", "{}").trending() }.isFailure)
    }

    private fun repository(path: String, response: String) = AnimeExploreRepository(
        AnimeTransport { url, body, headers ->
            assertEquals("https://api.animeko.org$path", url)
            assertNull(body)
            assertTrue(headers.getValue("User-Agent").startsWith("Aiyifan/"))
            response
        },
    )
}
