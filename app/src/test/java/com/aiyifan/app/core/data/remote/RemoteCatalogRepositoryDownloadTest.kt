package com.aiyifan.app.core.data.remote

import com.aiyifan.app.core.data.FakeCatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteCatalogRepositoryDownloadTest {
    @Test
    fun `download always requests a fresh matching source and preserves catalog identity`() = runBlocking {
        val fetcher = DownloadFetcher("""{"ret":200,"data":{"list":[
            {"episodeKey":"wrong","episodeId":999,"episodeTitle":"wrong","resolution":"720P","mediaUrl":"https://media.example/fresh.m3u8"},
            {"resolution":"1080P","mediaUrl":"https://media.example/default.mp4","isDefault":true}
        ]}}""")
        val repository = repository(fetcher)

        repeat(2) {
            val result = repository.resolveDownload(detail(), episode())
            assertEquals("https://media.example/fresh.m3u8", result.mediaUrl)
            assertEquals("720P", result.resolution)
            assertEquals("episode-1", result.episodeKey)
            assertEquals(3, result.uniqueId)
            assertEquals("第1集", result.episodeTitle)
        }
        assertEquals(2, fetcher.playbackRequests)
        assertTrue(fetcher.requestedUrl.orEmpty().contains("videoId=3&resolution=720P"))
    }

    @Test
    fun `download selects default source when requested quality is absent`() = runBlocking {
        val result = repository(DownloadFetcher("""{"data":{"list":[
            {"resolution":"480P","mediaUrl":"https://media.example/low.mp4"},
            {"resolution":"1080P","mediaUrl":"https://media.example/default.mp4","isDefault":true}
        ]}}""")).resolveDownload(detail(), episode())

        assertEquals("https://media.example/default.mp4", result.mediaUrl)
    }

    @Test
    fun `download rejects failed malformed and empty responses instead of stale url`() = runBlocking {
        listOf(
            HttpResponse(503, "{}"),
            HttpResponse(200, """{"ret":403,"data":{"list":[{"resolution":"720P","mediaUrl":"https://media.example/no.mp4"}]}}"""),
            HttpResponse(200, "not json"),
            HttpResponse(200, "{}"),
            HttpResponse(200, """{"data":{"list":[]}}"""),
            HttpResponse(200, """{"data":{"list":[42]}}"""),
            HttpResponse(200, """{"data":{"list":[{"resolution":"720P","mediaUrl":" "}]}}"""),
            HttpResponse(200, """{"data":{"list":[{"resolution":"720P","mediaUrl":"file:///tmp/local.mp4"}]}}"""),
            HttpResponse(200, """{"data":{"list":[{"resolution":"720P","mediaUrl":"https:///missing-host.mp4"}]}}"""),
        ).forEach { response ->
            try {
                repository(DownloadFetcher(response.body, response.code)).resolveDownload(detail(), episode())
                fail("Invalid response must not return the cached episode URL")
            } catch (expected: Exception) {
                assertTrue(expected !is UnsupportedOperationException)
            }
        }
    }

    @Test
    fun `download propagates network failure and cancellation`() = runBlocking {
        listOf(java.io.IOException("offline"), CancellationException("cancelled")).forEach { failure ->
            val fetcher = DownloadFetcher("{}", failure = failure)
            try {
                repository(fetcher).resolveDownload(detail(), episode())
                fail("Failure must propagate")
            } catch (actual: Exception) {
                assertTrue(actual === failure)
            }
        }
    }

    @Test
    fun `playback does not swallow cancellation during source refresh`() = runBlocking {
        val failure = CancellationException("cancelled")
        try {
            repository(DownloadFetcher("{}", failure = failure))
                .resolvePlayback(detail(), episode(), forceRefresh = true)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertTrue(actual === failure)
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `sample repository cannot resolve an offline download`() = runBlocking<Unit> {
        FakeCatalogRepository().resolveDownload(detail(), episode())
    }

    private fun repository(fetcher: HttpFetcher) = RemoteCatalogRepository(
        configResolver = RemoteConfigResolver(fetcher),
        fetcher = fetcher,
    )

    private fun detail() = VideoDetail("media-1", "Sample", "", 1)

    private fun episode() = Episode(
        episodeKey = "episode-1",
        episodeTitle = "第1集",
        uniqueId = 3,
        resolution = "720P",
        mediaUrl = "https://media.example/stale.mp4",
    )

    private class DownloadFetcher(
        private val body: String,
        private val code: Int = 200,
        private val failure: Exception? = null,
    ) : HttpFetcher {
        var playbackRequests = 0
        var requestedUrl: String? = null

        override suspend fun get(url: String): HttpResponse = when {
            url.endsWith("rawApp.json") -> HttpResponse(200, """{"api":"https://catalog.example"}""")
            url.contains("/api/Video/getPlayData?") -> {
                playbackRequests++
                requestedUrl = url
                failure?.let { throw it }
                HttpResponse(code, body)
            }
            else -> error("Unexpected request")
        }
    }
}
