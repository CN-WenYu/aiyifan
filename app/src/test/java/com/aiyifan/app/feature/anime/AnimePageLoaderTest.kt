package com.aiyifan.app.feature.anime

import com.aiyifan.app.core.data.anime.AnimePage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnimePageLoaderTest {
    @Test
    fun `append failure keeps earlier items and retry uses the same offset`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val loader = AnimePageLoader<String>(key = { it }) { offset ->
            offsets += offset
            when (offsets.size) {
                1 -> AnimePage(listOf("One"), 24)
                2 -> throw IOException("Offline")
                else -> AnimePage(listOf("One", "Two"), null)
            }
        }

        loader.loadNext()
        loader.loadNext()
        assertEquals(listOf("One"), loader.state.value.items)
        assertEquals(AnimeLoadStatus.FAILED, loader.state.value.status)
        loader.loadNext()
        loader.loadNext()
        assertEquals(listOf(0, 24, 24), offsets)
        assertEquals(listOf("One", "Two"), loader.state.value.items)
        assertNull(loader.state.value.nextOffset)
    }

    @Test
    fun `failed trending request does not affect the recommendations or an empty result`() = runBlocking {
        val trending = AnimePageLoader<String>(key = { it }) { throw IOException("Offline") }
        val recommended = AnimePageLoader<String>(key = { it }) { AnimePage(emptyList(), null) }

        trending.loadNext()
        recommended.loadNext()
        assertEquals(AnimeLoadStatus.FAILED, trending.state.value.status)
        assertEquals(AnimeLoadStatus.IDLE, recommended.state.value.status)
        assertTrue(recommended.state.value.items.isEmpty())
        assertNull(recommended.state.value.nextOffset)
    }
}
