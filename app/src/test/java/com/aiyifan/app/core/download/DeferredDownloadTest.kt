package com.aiyifan.app.core.download

import android.net.Uri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.common.StreamKey
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DeferredDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val episode = Episode("one", "01", 1, null)
    private val detail = VideoDetail("film", "影片", "", 1, episodes = listOf(episode, episode.copy(episodeKey = "two", episodeTitle = "02")))
    private val metadata = DownloadMetadata(detail, episode, 0, "generation1")

    @Test fun `unresolved metadata survives without a playback url and v1 remains readable`() {
        assertEquals(episode, DownloadMetadata.decode(metadata.encode())!!.episode)
        val resolved = metadata.copy(episode = episode.copy(mediaUrl = "https://example.test/v.m3u8"), generation = null)
        val legacy = resolved.encode().toString(Charsets.UTF_8).replace("\"version\":2", "\"version\":1")
        assertEquals(resolved.episode, DownloadMetadata.decode(legacy.toByteArray())!!.episode)
    }

    @Test fun `snapshots retain selected tracks and all episodes across store reconstruction`() {
        val dir = temporary.newFolder()
        val store = DownloadSnapshotStore(dir)
        val request = source("one")
        store.saveSource("generation1", request)
        store.saveFilm(detail)
        val restored = DownloadSnapshotStore(dir)
        assertEquals(request, restored.source("task")!!.request)
        assertEquals(listOf("one", "two"), restored.film("film")!!.episodes.map { it.episodeKey })
        restored.removeSource("task")
        assertNull(DownloadSnapshotStore(dir).source("task"))
    }

    @Test fun `partial catalog updates preserve episode order and refresh matching titles`() {
        val dir = temporary.newFolder()
        val store = DownloadSnapshotStore(dir)
        store.saveFilm(detail.copy(episodes = listOf(episode)))
        store.saveFilm(detail.copy(episodes = listOf(detail.episodes[1])))
        store.saveFilm(detail.copy(episodes = listOf(episode.copy(episodeTitle = "第一集"))))
        val restored = DownloadSnapshotStore(dir).film("film")!!
        assertEquals(listOf("one", "two"), restored.episodes.map { it.episodeKey })
        assertEquals("第一集", restored.episodes.first().episodeTitle)
    }

    @Test fun `failed fresh resolution preserves old snapshot and bytes then changed url clears cache`() {
        val store = DownloadSnapshotStore(temporary.newFolder())
        store.saveSource("older", source("old"))
        val outer = DownloadRequest.Builder("task", Uri.parse("offline-task://queued/task")).setData(metadata.encode()).build()
        var cleared = 0
        var delegated = false
        val delegate = object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) { delegated = true }
            override fun cancel() = Unit
            override fun remove() = Unit
        }
        try {
            DeferredDownloader(outer, store, { throw java.io.IOException("expired") }, { delegate }, { cleared++ }).download(null)
            fail("resolution should fail")
        } catch (_: java.io.IOException) { }
        assertEquals("https://example.test/old.m3u8", store.source("task")!!.request.uri.toString())
        assertEquals(0, cleared)
        DeferredDownloader(outer, store, { source("new") }, { delegate }, { cleared++ }).download(null)
        assertTrue(delegated)
        assertEquals(1, cleared)
        assertEquals("generation1", store.source("task")!!.generation)
        DeferredDownloader(outer, store, { error("resume must reuse source") }, { delegate }, { cleared++ }).download(null)
        assertEquals(1, cleared)
    }

    @Test fun `same url fresh resolution retains bytes and removal never resolves`() {
        val store = DownloadSnapshotStore(temporary.newFolder())
        store.saveSource("older", source("same"))
        val outer = DownloadRequest.Builder("task", Uri.parse("offline-task://queued/task")).setData(metadata.encode()).build()
        var cleared = 0
        val delegate = object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) = Unit
            override fun cancel() = Unit
            override fun remove() = Unit
        }
        val downloader = DeferredDownloader(outer, store, { source("same") }, { delegate }, { cleared++ })
        downloader.download(null)
        assertEquals(0, cleared)
        DeferredDownloader(outer, store, { error("remove must not resolve") }, { error("remove must not create delegate") }, { cleared++ }).remove()
        assertEquals(1, cleared)
        assertNull(store.source("task"))
    }

    private fun source(name: String) = DownloadRequest.Builder("task", Uri.parse("https://example.test/$name.m3u8"))
        .setMimeType("application/x-mpegURL").setStreamKeys(listOf(StreamKey(0, 0, 1), StreamKey(0, 1, 0)))
        .setData(metadata.copy(episode = episode.copy(mediaUrl = "https://example.test/$name.m3u8")).encode()).build()
}
