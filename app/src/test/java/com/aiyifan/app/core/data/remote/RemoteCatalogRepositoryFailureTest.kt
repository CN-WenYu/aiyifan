package com.aiyifan.app.core.data.remote

import com.aiyifan.app.core.model.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogRepositoryFailureTest {
    @Test
    fun `failed initial home and category requests never return demo content`() = runBlocking {
        val repository = repository()
        assertTrue(runCatching { repository.getCategories() }.isFailure)
        assertTrue(runCatching { repository.getHomeVideos("电影") }.isFailure)
        assertTrue(runCatching { repository.getHotVideos() }.isFailure)
        assertTrue(runCatching { repository.getHomeVideoPage(Category("3", "电影", 1, 0), 1) }.isFailure)
    }

    @Test
    fun `failed detail request never returns demo detail`() = runBlocking {
        assertTrue(runCatching { repository().getVideoDetail("movie-mc") }.isFailure)
    }

    @Test
    fun `comments without a real source are empty`() {
        assertTrue(repository().getComments("movie-mc").isEmpty())
    }

    @Test
    fun `failed search and suggestions remain failures without cached matches`() = runBlocking {
        assertTrue(runCatching { repository().searchVideos("迷城") }.isFailure)
        assertTrue(runCatching { repository().searchSuggestions("迷城") }.isFailure)
    }

    @Test
    fun `successful empty search is empty even when real cached catalog matches`() = runBlocking {
        val repository = repository(search = HttpResponse(200, """{"ret":200,"data":{"list":[]}}"""), home = realHome)
        repository.getHotVideos()
        assertTrue(repository.searchVideos("真实").isEmpty())
    }

    @Test
    fun `failed search can use already loaded real catalog matches`() = runBlocking {
        val repository = repository(home = realHome)
        repository.getHotVideos()
        assertEquals(listOf("real-1"), repository.searchVideos("真实").map { it.mediaKey })
    }

    @Test
    fun `malformed search response is failure rather than empty success`() = runBlocking {
        assertTrue(runCatching { repository(search = HttpResponse(200, """{"ret":200,"data":null}""")).searchVideos("电影") }.isFailure)
    }

    @Test
    fun `successful empty navigation and home are empty rather than demo categories`() = runBlocking {
        val repository = repository(home = """{"ret":200,"data":{"list":[]}}""", navigation = HttpResponse(200, """{"ret":200,"data":{"list":[]}}"""))
        assertTrue(repository.getCategories().isEmpty())
        assertTrue(repository.getHotVideos().isEmpty())
    }

    @Test
    fun `search cancellation is propagated even with cached matches`() = runBlocking {
        val fetcher = object : HttpFetcher {
            override suspend fun get(url: String) = if (url.endsWith("rawApp.json")) HttpResponse(200, """{"api":"https://catalog.example"}""") else HttpResponse(200, realHome)
            override suspend fun postForm(url: String, params: Map<String, String>): HttpResponse = throw CancellationException("cancelled")
        }
        val repository = RemoteCatalogRepository(configResolver = RemoteConfigResolver(fetcher), fetcher = fetcher)
        repository.getHotVideos()
        assertTrue(runCatching { repository.searchVideos("真实") }.exceptionOrNull() is CancellationException)
    }

    private fun repository(search: HttpResponse = HttpResponse(503, ""), home: String? = null, navigation: HttpResponse = HttpResponse(503, "")): RemoteCatalogRepository {
        val fetcher = object : HttpFetcher {
            override suspend fun get(url: String): HttpResponse = when {
                url.endsWith("rawApp.json") -> HttpResponse(200, """{"api":"https://catalog.example"}""")
                url.endsWith("api/List/Index") && home != null -> HttpResponse(200, home)
                else -> HttpResponse(503, "")
            }
            override suspend fun postForm(url: String, params: Map<String, String>) = search
            override suspend fun postJson(url: String, body: String) = navigation
        }
        return RemoteCatalogRepository(configResolver = RemoteConfigResolver(fetcher), fetcher = fetcher)
    }

    private val realHome = """{"ret":200,"data":{"list":[{"name":"电影","list":[{"mediaKey":"real-1","title":"真实影片","videoType":1}]}]}}"""
}
