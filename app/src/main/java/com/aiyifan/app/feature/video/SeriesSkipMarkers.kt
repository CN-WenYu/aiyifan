package com.aiyifan.app.feature.video

internal data class SeriesSkipMarkers(
    val introSeconds: Long? = null,
    val outroSeconds: Long? = null,
) {
    companion object {
        const val MAX_MINUTES = 999L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_SECONDS = MAX_MINUTES * SECONDS_PER_MINUTE + 59L

        fun fromPlayback(positionMs: Long, durationMs: Long): SeriesSkipMarkers {
            if (positionMs <= 0L || positionMs >= durationMs) return SeriesSkipMarkers()
            fun validSeconds(valueMs: Long): Long? {
                val wholeSeconds = valueMs / MILLIS_PER_SECOND
                val rounded = wholeSeconds + if (valueMs % MILLIS_PER_SECOND >= 500L) 1L else 0L
                return rounded.takeIf { it in 1L..MAX_SECONDS && it * MILLIS_PER_SECOND < durationMs }
            }
            return SeriesSkipMarkers(validSeconds(positionMs), validSeconds(durationMs - positionMs))
        }
    }
}
