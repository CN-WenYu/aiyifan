package com.aiyifan.app.core.data.anime

import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.data.FavoriteStore
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AnimeCatalogRepository(
    private val remote: CatalogRepository,
    private val anime: AnimeCatalog,
    private val favorites: FavoriteStore,
    private val enabled: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogRepository by remote {
    override suspend fun getVideoDetail(mediaKey: String): VideoDetail =
        if (mediaKey.isAnimeKey()) animeDetail(mediaKey) else remote.getVideoDetail(mediaKey)

    override suspend fun refreshVideoDetail(mediaKey: String): VideoDetail =
        if (mediaKey.isAnimeKey()) animeDetail(mediaKey, refresh = true) else remote.refreshVideoDetail(mediaKey)

    override suspend fun resolvePlayback(detail: VideoDetail, episode: Episode, forceRefresh: Boolean): Episode =
        if (!detail.mediaKey.isAnimeKey()) remote.resolvePlayback(detail, episode, forceRefresh)
        else if (enabled) anime.resolvePlayback(detail, episode)
        else throw AnimeSourceUnavailable()

    override suspend fun resolveDownload(detail: VideoDetail, episode: Episode): Episode {
        if (detail.mediaKey.isAnimeKey()) throw UnsupportedOperationException("Anime downloads are unavailable")
        return remote.resolveDownload(detail, episode)
    }

    override fun getComments(mediaKey: String) =
        if (mediaKey.isAnimeKey()) emptyList() else remote.getComments(mediaKey)

    override suspend fun checkFavoriteUpdates(mediaKey: String, force: Boolean) {
        if (!mediaKey.isAnimeKey()) {
            remote.checkFavoriteUpdates(mediaKey, force)
            return
        }
        if (!enabled) return
        val generation = favorites.generation(mediaKey) ?: return
        val checkedAt = favorites.tracking(mediaKey)?.checkedAt
        if (!force && checkedAt != null && clock() - checkedAt in 0 until 15 * 60_000L) return
        val detail = refreshVideoDetail(mediaKey)
        currentCoroutineContext().ensureActive()
        favorites.update(detail, clock(), generation)
    }

    override fun markFavoriteEpisodesKnown(detail: VideoDetail) {
        if (!detail.mediaKey.isAnimeKey()) remote.markFavoriteEpisodesKnown(detail)
        else if (enabled) favorites.markKnown(detail)
    }

    override fun getFavorites() = remote.getFavorites().filter { enabled || !it.mediaKey.isAnimeKey() }

    override fun getHistory() = remote.getHistory().filter { enabled || !it.mediaKey.isAnimeKey() }

    private suspend fun animeDetail(mediaKey: String, refresh: Boolean = false): VideoDetail {
        if (!enabled) throw AnimeHttpException(404)
        return anime.detail(mediaKey, refresh)
    }
}
