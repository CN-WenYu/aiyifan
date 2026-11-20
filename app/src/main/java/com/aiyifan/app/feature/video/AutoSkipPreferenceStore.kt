package com.aiyifan.app.feature.video

import android.content.Context

class AutoSkipPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_AUTO_SKIP, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_SKIP, enabled).apply()
    }

    fun getSeriesTimes(mediaKey: String): SeriesSkipTimes = SeriesSkipTimes(
        introSeconds = preferences.getLong("series_intro:$mediaKey", 0L),
        outroSeconds = preferences.getLong("series_outro:$mediaKey", 0L),
    )

    fun setSeriesTimes(mediaKey: String, times: SeriesSkipTimes) {
        preferences.edit().apply {
            if (times.introSeconds > 0L) putLong("series_intro:$mediaKey", times.introSeconds)
            else remove("series_intro:$mediaKey")
            if (times.outroSeconds > 0L) putLong("series_outro:$mediaKey", times.outroSeconds)
            else remove("series_outro:$mediaKey")
        }.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "playback"
        const val KEY_AUTO_SKIP = "auto_skip_intro_outro"
    }
}
