package com.aiyifan.app.feature.video

data class SeriesSkipTimes(val introSeconds: Long = 0L, val outroSeconds: Long = 0L)

object AutoSkipPolicy {
    data class EpisodeTimes(val introMs: Long = 0L, val outroStartMs: Long? = null)

    fun episodeTimes(
        durationMs: Long,
        introSecond: Long?,
        outroSecond: Long?,
        custom: SeriesSkipTimes,
    ): EpisodeTimes {
        if (durationMs <= 0L) return EpisodeTimes()
        val serverIntro = introSecond?.toMillisecondsOrNull(true)?.takeIf { it < durationMs } ?: 0L
        val serverOutro = outroSecond?.toMillisecondsOrNull(true)
            ?.takeIf { it > serverIntro && it < durationMs }
        val intro = custom.introSeconds.toMillisecondsOrNull(true)?.takeIf { it < durationMs } ?: serverIntro
        val outro = custom.outroSeconds.toMillisecondsOrNull(true)
            ?.takeIf { it < durationMs }
            ?.let { durationMs - it } ?: serverOutro
        return if (outro != null && intro >= outro) EpisodeTimes(serverIntro, serverOutro)
        else EpisodeTimes(intro, outro)
    }

    fun initialPositionMs(
        resumePositionMs: Long,
        introSecond: Long?,
        enabled: Boolean,
    ): Long =
        resumePositionMs.takeIf { it > 0L }
            ?: introSecond?.toMillisecondsOrNull(enabled)
            ?: 0L

    fun shouldAdvance(
        positionMs: Long,
        outroSecond: Long?,
        enabled: Boolean,
        hasNextEpisode: Boolean,
        hasAdvanced: Boolean,
    ): Boolean =
        enabled &&
            hasNextEpisode &&
            !hasAdvanced &&
            outroSecond?.toMillisecondsOrNull(enabled)?.let { positionMs >= it } == true

    private fun Long.toMillisecondsOrNull(enabled: Boolean): Long? =
        takeIf { enabled && it in 1L..MAX_SAFE_SECOND }?.times(MILLIS_PER_SECOND)

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_SAFE_SECOND = Long.MAX_VALUE / MILLIS_PER_SECOND
}
