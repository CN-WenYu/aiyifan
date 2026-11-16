package com.aiyifan.app.core.download

import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import org.junit.Assert.*
import org.junit.Test

class DownloadMetadataTest {
    private val episode = Episode("ep:一", "第一集", 42, "https://cdn.example/video.m3u8?token=a%2Bb&expires=3", lang = "中文", opSecond = 4, epSecond = 99)
    private val detail = VideoDetail("media:一", "节目", "https://cover.example/1.jpg", 2, episodes = listOf(episode))

    @Test fun `identity cannot collide when delimiters appear in original keys`() {
        assertNotEquals(downloadId("a:b", "c"), downloadId("a", "b:c"))
        assertEquals(downloadId("a:b", "c"), downloadId("a:b", "c"))
        assertNotEquals(downloadId("a", "1"), downloadId("a", "2"))
    }

    @Test fun `metadata survives persistence with original keys signed url and skip positions`() {
        val original = DownloadMetadata(detail, episode, 7)
        val restored = DownloadMetadata.decode(original.encode())!!
        assertEquals(detail.mediaKey, restored.detail.mediaKey)
        assertEquals(detail.title, restored.detail.title)
        assertEquals(detail.coverUrl, restored.detail.coverUrl)
        assertEquals(episode, restored.episode)
        assertEquals(7, restored.episodeOrder)
        assertEquals(listOf(episode), restored.detail.episodes)
    }

    @Test fun `invalid and unknown version metadata is safely ignored`() {
        assertNull(DownloadMetadata.decode(byteArrayOf()))
        assertNull(DownloadMetadata.decode("not json".toByteArray()))
        assertNull(DownloadMetadata.decode("{\"version\":99}".toByteArray()))
        assertNull(DownloadMetadata.decode("{\"version\":1}".toByteArray()))
    }

    @Test fun `retry compares full signed uri and never strips query parameters`() {
        assertFalse(requiresDownloadReplacement("https://c/v?token=one", "https://c/v?token=one"))
        assertTrue(requiresDownloadReplacement("https://c/v?token=one", "https://c/v?token=two"))
    }
}
