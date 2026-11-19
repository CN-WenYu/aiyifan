package com.aiyifan.app.core.data

import android.content.SharedPreferences
import com.aiyifan.app.core.model.WatchHistory

class SharedPreferencesWatchHistoryStore(
    private val preferences: SharedPreferences,
) : WatchHistoryStore {
    override fun save(history: WatchHistory) {
        if (!history.isValid()) return
        synchronized(preferences) {
            val records = (readAll() + history)
                .filter { it.isValid() }
                .groupBy { recordKey(it.mediaKey, it.episodeKey) }
                .values
                .map { values -> values.maxBy { it.updatedAt } }
            preferences.edit().putString(HISTORY_KEY, WatchHistoryCodec.encode(records)).apply()
        }
    }

    override fun getHistory(): List<WatchHistory> = synchronized(preferences) {
        readAll().let { records ->
            val store = InMemoryWatchHistoryStore()
            records.forEach(store::save)
            store.getHistory()
        }
    }

    override fun getEpisodeHistory(mediaKey: String, episodeKey: String): WatchHistory? = synchronized(preferences) {
        readAll().firstOrNull { it.mediaKey == mediaKey && it.episodeKey == episodeKey }
    }

    override fun remove(mediaKeys: Set<String>) {
        synchronized(preferences) {
            val remaining = readAll().filterNot { it.mediaKey in mediaKeys }
            preferences.edit().putString(HISTORY_KEY, WatchHistoryCodec.encode(remaining)).apply()
        }
    }

    override fun clear() {
        synchronized(preferences) {
            preferences.edit().remove(HISTORY_KEY).apply()
        }
    }

    private companion object {
        const val HISTORY_KEY = "watch_history"
    }

    private fun readAll(): List<WatchHistory> {
        val payload = try { preferences.getString(HISTORY_KEY, null) } catch (_: ClassCastException) { null }
        return WatchHistoryCodec.decodeAll(payload)
    }

}
