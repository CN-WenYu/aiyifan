package com.aiyifan.app.core.data

import android.content.Context
import com.aiyifan.app.BuildConfig
import com.aiyifan.app.core.data.anime.AnimeCatalog
import com.aiyifan.app.core.data.anime.AnimeCatalogRepository
import com.aiyifan.app.core.data.anime.AnimeExploreRepository
import com.aiyifan.app.core.data.anime.BangumiClient
import com.aiyifan.app.core.data.anime.HttpsAnimeTransport
import com.aiyifan.app.core.data.anime.XifanSource
import com.aiyifan.app.core.data.remote.ChannelNavigationRepository
import com.aiyifan.app.core.data.remote.RemoteCatalogRepository
import com.aiyifan.app.core.data.remote.RemoteConfigResolver
import com.aiyifan.app.core.data.remote.UrlConnectionHttpFetcher
import com.aiyifan.app.feature.video.VideoPlaybackController
import com.aiyifan.app.feature.video.VideoPlaybackControllerProvider

object AppGraph {
    private lateinit var applicationContext: Context

    val channelNavigationRepository: ChannelNavigationRepository by lazy {
        ChannelNavigationRepository()
    }

    internal val animeCatalog by lazy {
        val transport = HttpsAnimeTransport()
        AnimeCatalog(BangumiClient(transport), XifanSource(transport))
    }

    internal val animeExplore by lazy { AnimeExploreRepository(HttpsAnimeTransport()) }

    val catalogRepository: CatalogRepository by lazy {
        val favorites = SharedPreferencesFavoriteStore(
            applicationContext.getSharedPreferences("favorites", Context.MODE_PRIVATE),
        )
        val remote = RemoteCatalogRepository(
            configResolver = RemoteConfigResolver(UrlConnectionHttpFetcher()),
            fetcher = UrlConnectionHttpFetcher(),
            favoriteStore = favorites,
            historyStore = SharedPreferencesWatchHistoryStore(
                applicationContext.getSharedPreferences("watch_history", Context.MODE_PRIVATE),
            ),
        )
        AnimeCatalogRepository(remote, animeCatalog, favorites, BuildConfig.ANIME_ENABLED)
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val downloadRepository: com.aiyifan.app.core.download.OfflineDownloadRepository by lazy {
        com.aiyifan.app.core.download.OfflineDownloadRepository(applicationContext, catalogRepository) { id ->
            videoPlaybackControllerProvider.stopOfflineDownload(id)
        }
    }

    private val videoPlaybackControllerProvider by lazy {
        VideoPlaybackControllerProvider {
            check(::applicationContext.isInitialized) {
                "AppGraph must be initialized from AiyifanApp before requesting the video controller."
            }
            VideoPlaybackController.create(
                applicationContext = applicationContext,
                repository = catalogRepository,
                downloads = downloadRepository,
            )
        }
    }

    val videoPlaybackController: VideoPlaybackController
        get() = videoPlaybackControllerProvider.get()
}
