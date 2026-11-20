package com.aiyifan.app.feature.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.datasource.DefaultDataSource
import com.aiyifan.app.core.download.OfflineDownloadRepository
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.model.Episode
import com.aiyifan.app.core.model.VideoDetail
import com.aiyifan.app.core.ui.PlaybackScreenAwakeController
import com.aiyifan.app.core.ui.ScreenOffPlaybackObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface PlaybackEngine {
    val isPlaying: Boolean
    val playWhenReady: Boolean get() = isPlaying
    val isReady: Boolean get() = true
    val hasEnded: Boolean get() = false
    val currentPosition: Long
    val duration: Long

    val hasPlaybackError: Boolean get() = false

    fun setOfflineMedia(downloadId: String) { error("Offline playback is unavailable") }

    fun setMediaUrl(mediaUrl: String)

    fun prepare()

    fun seekTo(positionMs: Long)

    fun addPositionListener(listener: (Long) -> Unit): () -> Unit
    fun addStateListener(listener: () -> Unit): () -> Unit = {}
    fun addSeekListener(listener: () -> Unit): () -> Unit = {}

    fun play()

    fun pause()

    fun attach(playerView: PlayerView)

    fun detach()

    fun release()
}

interface PlaybackSession {
    fun release()
}

data class ActivePlaybackSession(
    val detail: VideoDetail,
    val episode: Episode,
    val offlineDownloadId: String? = null,
)

sealed interface EpisodeSwitchResult {
    data class Switched(val episode: Episode) : EpisodeSwitchResult

    data object Unavailable : EpisodeSwitchResult

    data object Failed : EpisodeSwitchResult
}

class VideoPlaybackController(
    private val engine: PlaybackEngine,
    private val repository: CatalogRepository,
    private val session: PlaybackSession,
    private val canPlay: () -> Boolean = { true },
    private val skipIntro: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val offlineCatalog: OfflinePlaybackCatalog? = null,
    monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val seriesSkipTimes: (String) -> SeriesSkipTimes = { SeriesSkipTimes() },
) {
    private val sleepTimer = SleepTimer(monotonicClock)
    private var stoppedForSleep = false
    private var removeTimerTicker: (() -> Unit)? = null
    val sleepTimerState: SleepTimerState get() = sleepTimer.state

    fun setSleepTimer(option: SleepTimerOption) {
        if (released) return
        sleepTimer.set(option, activeDetail?.mediaKey, activeEpisode?.episodeKey)
        notifyState()
    }

    /** Called independently of engine progress, including while paused or buffering. */
    fun tickSleepTimer() {
        if (released) return
        if (consumeSleepExpiry()) return
        if (sleepTimer.state.option != SleepTimerOption.OFF) notifyState()
    }

    private fun consumeSleepExpiry(): Boolean {
        if (!sleepTimer.consumeExpiry(activeDetail?.mediaKey, activeEpisode?.episodeKey, engine.hasEnded)) return false
        // Latch before pause/notification: engine and UI callbacks may be synchronous/reentrant.
        stoppedForSleep = true
        pause()
        return true
    }

    var wasOfflineDownloadRemoved: Boolean = false
        private set
    var selectionToken: Long = 0L
        private set
    private var offlineLoadFailed = false
    private var activeOfflineDownloadId: String? = null
    private var offlineSession = false
    var requestedOfflineDownloadId: String? = null
        private set
    val isOffline: Boolean get() = offlineSession
    val hasOfflinePlaybackError: Boolean get() = offlineLoadFailed || activeOfflineDownloadId != null && engine.hasPlaybackError
    val hasPlaybackError: Boolean get() = !released && (engine.hasPlaybackError || hasOfflinePlaybackError)

    private var activeDetail: VideoDetail? = null
    private var activeEpisode: Episode? = null
    private var activeSkipTimes = SeriesSkipTimes()
    private var pendingInitialIntro = false
    private var released = false
    private var paused = false
    private var replacingMedia = false
    private var endedAdvanceEpisodeKey: String? = null
    private var automaticAdvanceAtEnd = false
    private var requestVersion = 0L
    private var lastSaveAt = 0L
    private var lastPlayWhenReady = engine.playWhenReady
    private val stateListeners = linkedSetOf<() -> Unit>()
    private var screenObserver: ScreenOffPlaybackObserver? = null
    var isLoading: Boolean = false
        private set
    var pendingEpisode: Episode? = null
        private set
    var hasOverlayOwner: Boolean = false
    var requestedMediaKey: String? = null
        private set
    val playWhenReady: Boolean get() = !released && engine.playWhenReady

    private val removeStateListener = engine.addStateListener {
        if (!replacingMedia && !released) {
            if (consumeSleepExpiry()) return@addStateListener
            if (engine.playWhenReady && !lastPlayWhenReady) stoppedForSleep = false
            // Leaving the ended state (for example, replaying after a failed request)
            // starts a new completion attempt without retrying repeated ended callbacks.
            if (!engine.hasEnded && automaticAdvanceAtEnd) {
                endedAdvanceEpisodeKey = null
                automaticAdvanceAtEnd = false
            }
            applyPendingIntro()
            if (engine.playWhenReady && !canPlay()) pause()
            else {
                if (lastPlayWhenReady != engine.playWhenReady) paused = !engine.playWhenReady
                lastPlayWhenReady = engine.playWhenReady
                if (!engine.playWhenReady || engine.hasEnded) saveHistory()
                notifyState()
            }
        }
    }
    private val removeProgressListener = engine.addPositionListener {
        applyPendingIntro()
        if (clock() - lastSaveAt >= 5_000L) saveHistory()
    }
    private val removeSeekListener = engine.addSeekListener {
        if (!replacingMedia) {
            pendingInitialIntro = false
            saveHistory()
            notifyState()
        }
    }

    /** Claims this shared controller when navigating between two video pages. */
    fun openVideo(mediaKey: String, offlineDownloadId: String? = null) {
        if (requestedMediaKey == mediaKey && requestedOfflineDownloadId == offlineDownloadId) return
        selectionToken++
        val sourceChanged = requestedOfflineDownloadId != offlineDownloadId
        sleepTimer.onSelection(mediaKey, null)
        saveHistory()
        requestVersion++
        isLoading = false
        pendingEpisode = null
        endedAdvanceEpisodeKey = null
        automaticAdvanceAtEnd = false
        pendingInitialIntro = false
        requestedMediaKey = mediaKey
        requestedOfflineDownloadId = offlineDownloadId
        offlineSession = offlineDownloadId != null
        offlineLoadFailed = false
        if (activeDetail?.mediaKey != mediaKey || sourceChanged) {
            replacingMedia = true
            engine.pause()
            lastPlayWhenReady = false
            replacingMedia = false
            paused = stoppedForSleep || !canPlay()
            activeDetail = null
            activeEpisode = null
            activeOfflineDownloadId = null
        }
    }

    val isPrepared: Boolean
        get() = !released && activeDetail != null && activeEpisode != null

    val isReleased: Boolean
        get() = released

    val isPlaying: Boolean
        get() = isPrepared && engine.isPlaying

    val currentPositionMs: Long
        get() = if (released) 0L else engine.currentPosition.coerceAtLeast(0L)

    val durationMs: Long
        get() = if (released) 0L else engine.duration.coerceAtLeast(0L)

    val canSeek: Boolean
        get() = isPrepared && !isLoading && engine.isReady && durationMs > 0L

    val isPlaybackEnded: Boolean
        get() = isPrepared && engine.hasEnded &&
            (requestedMediaKey == null || requestedMediaKey == activeDetail?.mediaKey)

    val activeSession: ActivePlaybackSession?
        get() = activeDetail?.let { detail ->
            activeEpisode?.let { episode -> ActivePlaybackSession(detail, episode, activeOfflineDownloadId) }
        }

    fun prepare(
        detail: VideoDetail,
        episode: Episode,
        startPositionMs: Long = 0L,
        shouldPlay: Boolean = true,
        offlineDownloadId: String? = null,
    ): Boolean = prepareEpisode(detail, episode, startPositionMs, shouldPlay, offlineDownloadId, applyIntro = false)

    private fun prepareEpisode(
        detail: VideoDetail,
        episode: Episode,
        startPositionMs: Long,
        shouldPlay: Boolean = true,
        offlineDownloadId: String? = null,
        applyIntro: Boolean = true,
    ): Boolean {
        val mediaUrl = episode.mediaUrl?.trim().orEmpty()
        if (released || mediaUrl.isBlank()) return false
        consumeSleepExpiry()
        sleepTimer.onSelection(detail.mediaKey, episode.episodeKey)
        saveHistory()
        requestVersion++
        isLoading = false
        pendingEpisode = null
        endedAdvanceEpisodeKey = null
        automaticAdvanceAtEnd = false
        replacingMedia = true
        try {
            if (offlineDownloadId == null) engine.setMediaUrl(mediaUrl) else engine.setOfflineMedia(offlineDownloadId)
            activeOfflineDownloadId = offlineDownloadId
            if (offlineDownloadId != null) requestedOfflineDownloadId = offlineDownloadId
            offlineLoadFailed = false
            activeDetail = detail
            activeEpisode = episode
            activeSkipTimes = seriesSkipTimes(detail.mediaKey)
            val autoSkip = applyIntro && skipIntro()
            pendingInitialIntro = autoSkip && startPositionMs <= 0L && activeSkipTimes.introSeconds > 0L
            engine.prepare()
            if (pendingInitialIntro) {
                applyPendingIntro()
            } else {
                val positionMs = AutoSkipPolicy.initialPositionMs(startPositionMs, episode.opSecond, autoSkip)
                if (positionMs > 0L) engine.seekTo(positionMs)
            }
            if (shouldPlay && !paused && !stoppedForSleep && canPlay()) engine.play() else engine.pause()
            paused = !engine.playWhenReady
            lastPlayWhenReady = engine.playWhenReady
            lastSaveAt = clock()
        } finally {
            replacingMedia = false
        }
        notifyState()
        return true
    }

    private fun applyPendingIntro() {
        if (!pendingInitialIntro || !engine.isReady || durationMs <= 0L) return
        pendingInitialIntro = false
        val episode = activeEpisode ?: return
        if (!skipIntro() || engine.hasEnded) return
        val introMs = episodeTimes(episode).introMs
        if (introMs > 0L) engine.seekTo(introMs)
    }

    private fun episodeTimes(episode: Episode) =
        AutoSkipPolicy.episodeTimes(durationMs, episode.opSecond, episode.epSecond, activeSkipTimes)

    fun attach(playerView: PlayerView) {
        if (!released) engine.attach(playerView)
    }

    fun detach() {
        engine.detach()
    }

    fun togglePlayPause() {
        if (!isPrepared) return

        if (engine.playWhenReady) {
            pause()
        } else if (canPlay()) {
            paused = false
            stoppedForSleep = false
            engine.play()
            notifyState()
        }
    }

    fun pause() {
        if (released) return
        paused = true
        lastPlayWhenReady = false
        engine.pause()
        saveHistory()
        notifyState()
    }

    fun seekTo(positionMs: Long) {
        if (isPrepared) {
            pendingInitialIntro = false
            engine.seekTo(positionMs.coerceAtLeast(0L))
            saveHistory()
        }
    }

    fun seekBy(offsetMs: Long) {
        if (!canSeek) return
        val duration = durationMs
        val position = currentPositionMs.coerceIn(0L, duration)
        seekTo(position + offsetMs.coerceIn(-position, duration - position))
    }

    private fun adjacentEpisode(detail: VideoDetail, episode: Episode, offset: Int): Episode? {
        val episodes = detail.episodes
        val activeIndex = episodes.indexOfFirst { it.episodeKey == episode.episodeKey }
        return if (activeIndex < 0) null else episodes.getOrNull(activeIndex + offset)
    }

    suspend fun switchEpisode(offset: Int): EpisodeSwitchResult {
        val (detail, episode) = activeSession ?: return EpisodeSwitchResult.Unavailable
        if (offset != -1 && offset != 1) return EpisodeSwitchResult.Unavailable
        val targetEpisode = adjacentEpisode(detail, episode, offset) ?: return EpisodeSwitchResult.Unavailable
        return loadEpisode(detail, targetEpisode)
    }

    suspend fun advanceToNextEpisodeIfEnded(): EpisodeSwitchResult = advanceAutomatically()

    private data class AutomaticTransition(
        val session: ActivePlaybackSession,
        val next: Episode,
        val downloadId: String?,
        val outroStartMs: Long?,
    )

    private fun automaticTransition(): AutomaticTransition? {
        if (released || replacingMedia || isLoading || stoppedForSleep || paused ||
            sleepTimer.state.option == SleepTimerOption.END_OF_EPISODE
        ) return null
        val session = activeSession ?: return null
        val alreadyAttempted = endedAdvanceEpisodeKey == session.episode.episodeKey
        if (alreadyAttempted && automaticAdvanceAtEnd) return null
        val next = adjacentEpisode(session.detail, session.episode, 1) ?: return null
        val downloadId = if (isOffline) {
            offlineCatalog?.completed(session.detail.mediaKey)
                ?.firstOrNull { it.episode.episodeKey == next.episodeKey }?.id ?: return null
        } else null
        // After a failed early switch, give natural completion its own attempt.
        val outroStartMs = if (!alreadyAttempted && skipIntro()) episodeTimes(session.episode).outroStartMs else null
        return AutomaticTransition(session, next, downloadId, outroStartMs)
    }

    val showNextEpisodeHint: Boolean
        get() {
            if (!isPlaying || !engine.isReady || pendingInitialIntro) return false
            val transition = automaticTransition() ?: return false
            val remainingMs = (transition.outroStartMs ?: durationMs) - currentPositionMs
            return remainingMs in 1L..10_000L
        }

    /** Single automatic transition gate shared by every playback surface and outro skipping. */
    suspend fun advanceAutomatically(positionMs: Long? = null): EpisodeSwitchResult {
        if (consumeSleepExpiry()) return EpisodeSwitchResult.Unavailable
        val transition = automaticTransition() ?: return EpisodeSwitchResult.Unavailable
        val ended = isPlaybackEnded
        val skipOutro = positionMs != null && engine.isPlaying && engine.isReady && !pendingInitialIntro &&
            transition.outroStartMs?.let { minOf(positionMs, currentPositionMs) >= it } == true
        if (!ended && !skipOutro) return EpisodeSwitchResult.Unavailable
        val current = transition.session.episode
        endedAdvanceEpisodeKey = current.episodeKey
        automaticAdvanceAtEnd = ended
        return try {
            val result = if (transition.downloadId != null) loadOfflineDownload(transition.downloadId)
            else loadEpisode(transition.session.detail, transition.next)
            result.also {
                if (result is EpisodeSwitchResult.Failed) {
                    pause()
                    // Keep the failed transition retryable after the player is paused.
                    paused = false
                }
            }
        } catch (error: CancellationException) {
            if (endedAdvanceEpisodeKey == current.episodeKey) {
                endedAdvanceEpisodeKey = null
                automaticAdvanceAtEnd = false
                notifyState()
            }
            throw error
        }
    }

    suspend fun loadEpisode(
        detail: VideoDetail,
        episode: Episode,
        resumePositionMs: Long = 0L,
    ): EpisodeSwitchResult {
        if (released || isLoading) return EpisodeSwitchResult.Unavailable
        if (requestedMediaKey != null && requestedMediaKey != detail.mediaKey) return EpisodeSwitchResult.Unavailable
        if (isOffline) {
            val entry = offlineCatalog?.completed(detail.mediaKey)?.firstOrNull { it.episode.episodeKey == episode.episodeKey }
            if (entry != null) return loadOfflineDownload(entry.id, resumePositionMs)
        }
        sleepTimer.onSelection(detail.mediaKey, episode.episodeKey)
        val version = ++requestVersion
        isLoading = true
        pendingEpisode = episode
        notifyState()
        try {
            // A stream variant must not replace the identity used by the catalog and history.
            val playable = repository.resolvePlayback(detail, episode).copy(
                episodeKey = episode.episodeKey,
                uniqueId = episode.uniqueId,
                episodeTitle = episode.episodeTitle,
            )
            currentCoroutineContext().ensureActive()
            if (released || version != requestVersion) return EpisodeSwitchResult.Unavailable
            return if (prepareEpisode(detail, playable, resumePositionMs)) EpisodeSwitchResult.Switched(playable)
            else EpisodeSwitchResult.Failed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return EpisodeSwitchResult.Failed
        } finally {
            if (version == requestVersion) {
                isLoading = false
                pendingEpisode = null
                notifyState()
            }
        }
    }

    /** Uses persisted metadata and completed cache entries only; never resolves a remote URL. */
    fun loadOfflineDownload(downloadId: String, resumePositionMs: Long = 0L): EpisodeSwitchResult {
        if (released || isLoading || !isOffline) return EpisodeSwitchResult.Unavailable
        val catalog = offlineCatalog ?: return offlineFailure()
        val entry = catalog.get(downloadId) ?: return offlineFailure()
        if (entry.detail.mediaKey != requestedMediaKey) return offlineFailure()
        val completed = catalog.completed(entry.detail.mediaKey).sortedBy { it.episodeOrder }
        if (completed.none { it.id == downloadId }) return offlineFailure()
        val snapshot = (catalog.snapshot(entry.detail.mediaKey) ?: entry.detail).copy(related = emptyList())
        return try {
            if (prepareEpisode(snapshot, entry.episode, resumePositionMs, offlineDownloadId = downloadId)) {
                EpisodeSwitchResult.Switched(entry.episode)
            } else offlineFailure()
        } catch (_: Exception) {
            offlineFailure()
        }
    }

    private fun offlineFailure(): EpisodeSwitchResult {
        offlineLoadFailed = true
        notifyState()
        return EpisodeSwitchResult.Failed
    }

    fun stopOfflineDownload(downloadId: String) {
        if (activeOfflineDownloadId == downloadId || requestedOfflineDownloadId == downloadId) {
            wasOfflineDownloadRemoved = true
            release()
        }
    }

    fun addStateListener(listener: () -> Unit): () -> Unit {
        stateListeners += listener
        listener()
        return { stateListeners -= listener }
    }

    private fun notifyState() = stateListeners.toList().forEach { it() }

    fun addPositionListener(listener: (Long) -> Unit): () -> Unit = engine.addPositionListener(listener)

    fun saveHistory() {
        val detail = activeDetail ?: return
        val episode = activeEpisode ?: return
        if (released || replacingMedia || (!engine.isReady && engine.currentPosition <= 0L)) return

        repository.saveHistory(
            detail = detail,
            episode = episode,
            progressMs = engine.currentPosition.coerceAtLeast(0L),
            durationMs = engine.duration.coerceAtLeast(0L),
        )
        lastSaveAt = clock()
    }

    fun release() {
        if (released) return

        saveHistory()
        released = true
        sleepTimer.clear()
        removeTimerTicker?.invoke()
        removeTimerTicker = null
        requestVersion++
        isLoading = false
        pendingEpisode = null
        endedAdvanceEpisodeKey = null
        screenObserver?.release()
        removeProgressListener()
        removeStateListener()
        removeSeekListener()
        notifyState()
        stateListeners.clear()
        engine.detach()
        session.release()
        engine.release()
    }

    companion object {
        @AndroidxOptIn(markerClass = [UnstableApi::class])
        fun create(
            applicationContext: Context,
            repository: CatalogRepository,
            downloads: OfflineDownloadRepository? = null,
        ): VideoPlaybackController {
            val autoSkipPreferences = AutoSkipPreferenceStore(applicationContext)
            val player = ExoPlayer.Builder(applicationContext)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(applicationContext)))
                .build()
            val session = MediaSession.Builder(applicationContext, player).build()
            return VideoPlaybackController(
                engine = Media3PlaybackEngine(player, downloads),
                offlineCatalog = downloads?.let(::RepositoryOfflinePlaybackCatalog),
                repository = repository,
                session = Media3PlaybackSession(session),
                canPlay = { ScreenOffPlaybackObserver.canPlay(applicationContext) },
                skipIntro = autoSkipPreferences::isEnabled,
                seriesSkipTimes = autoSkipPreferences::getSeriesTimes,
                monotonicClock = android.os.SystemClock::elapsedRealtime,
            ).also { controller ->
                controller.screenObserver = ScreenOffPlaybackObserver(applicationContext, controller::pause)
                val timerHandler = Handler(Looper.getMainLooper())
                val timerTick = object : Runnable {
                    override fun run() {
                        if (controller.isReleased) return
                        controller.tickSleepTimer()
                        timerHandler.postDelayed(this, 1_000L)
                    }
                }
                timerHandler.post(timerTick)
                controller.removeTimerTicker = { timerHandler.removeCallbacks(timerTick) }
            }
        }
    }
}

class VideoPlaybackControllerProvider(
    private val createController: () -> VideoPlaybackController,
) {
    private var controller: VideoPlaybackController? = null

    @Synchronized
    fun stopOfflineDownload(id: String) {
        controller?.stopOfflineDownload(id)
    }

    @Synchronized
    fun get(): VideoPlaybackController {
        val activeController = controller
        if (activeController != null && !activeController.isReleased) return activeController

        return createController().also { controller = it }
    }
}

private class Media3PlaybackEngine(
    private val player: ExoPlayer,
    private val downloads: OfflineDownloadRepository?,
) : PlaybackEngine {
    private var attachedPlayerView: PlayerView? = null
    private var screenAwakeController: PlaybackScreenAwakeController? = null
    private val positionListeners = linkedSetOf<(Long) -> Unit>()
    private val stateListeners = linkedSetOf<() -> Unit>()
    private val seekListeners = linkedSetOf<() -> Unit>()
    private var pendingInitialSeek = false
    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            if (!player.isPlaying) return

            positionListeners.toList().forEach { listener -> listener(player.currentPosition.coerceAtLeast(0L)) }
            positionHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekListeners.toList().forEach { it() }
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (pendingInitialSeek && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)) {
                pendingInitialSeek = false
                if (player.duration > 0 && player.currentPosition >= player.duration) player.seekTo(0L)
            }
            stateListeners.toList().forEach { it() }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            screenAwakeController?.onIsPlayingChanged(isPlaying)
            if (isPlaying) startPositionUpdates() else positionHandler.removeCallbacks(positionRunnable)
        }
    }

    init {
        player.addListener(playerListener)
    }

    override val isPlaying: Boolean
        get() = player.isPlaying
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val isReady: Boolean
        get() = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
    override val hasEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    override val hasPlaybackError: Boolean get() = player.playerError != null

    override fun addSeekListener(listener: () -> Unit): () -> Unit {
        seekListeners += listener
        return { seekListeners -= listener }
    }

    override fun addStateListener(listener: () -> Unit): () -> Unit {
        stateListeners += listener
        return { stateListeners -= listener }
    }

    override val currentPosition: Long
        get() = player.currentPosition

    override val duration: Long
        get() = player.duration

    @AndroidxOptIn(markerClass = [UnstableApi::class])
    override fun setOfflineMedia(downloadId: String) {
        val repository = requireNotNull(downloads)
        val task = requireNotNull(repository.get(downloadId))
        check(task.download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
        pendingInitialSeek = true
        player.setMediaSource(
            DefaultMediaSourceFactory(repository.offlineDataSourceFactory(downloadId))
                .createMediaSource(repository.offlineRequest(downloadId).toMediaItem()),
        )
    }

    override fun setMediaUrl(mediaUrl: String) {
        pendingInitialSeek = true
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(mediaUrl))
    }

    override fun prepare() {
        player.prepare()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun addPositionListener(listener: (Long) -> Unit): () -> Unit {
        positionListeners += listener
        if (player.isPlaying) startPositionUpdates()
        return {
            positionListeners -= listener
            if (positionListeners.isEmpty()) positionHandler.removeCallbacks(positionRunnable)
        }
    }

    override fun play() {
        player.play()
        startPositionUpdates()
    }

    override fun pause() {
        player.pause()
    }

    override fun attach(playerView: PlayerView) {
        if (attachedPlayerView === playerView) return

        screenAwakeController?.detach()
        attachedPlayerView?.player = null
        playerView.player = player
        attachedPlayerView = playerView
        screenAwakeController = PlaybackScreenAwakeController(playerView::setKeepScreenOn).also {
            it.attach(player.isPlaying)
        }
    }

    override fun detach() {
        screenAwakeController?.detach()
        screenAwakeController = null
        attachedPlayerView?.player = null
        attachedPlayerView = null
    }

    override fun release() {
        detach()
        positionHandler.removeCallbacks(positionRunnable)
        positionListeners.clear()
        stateListeners.clear()
        seekListeners.clear()
        player.removeListener(playerListener)
        player.release()
    }

    private fun startPositionUpdates() {
        positionHandler.removeCallbacks(positionRunnable)
        positionHandler.post(positionRunnable)
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 1_000L
    }
}

private class Media3PlaybackSession(
    private val session: MediaSession,
) : PlaybackSession {
    override fun release() {
        session.release()
    }
}
