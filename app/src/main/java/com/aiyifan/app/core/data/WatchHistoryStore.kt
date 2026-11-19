package com.aiyifan.app.core.data

import com.aiyifan.app.core.model.WatchHistory

interface WatchHistoryStore {
    fun save(history: WatchHistory)
    fun getHistory(): List<WatchHistory>
    fun getEpisodeHistory(mediaKey: String, episodeKey: String): WatchHistory?
    fun remove(mediaKeys: Set<String>)
    fun clear()
}

class InMemoryWatchHistoryStore : WatchHistoryStore {
    private val records = linkedMapOf<String, WatchHistory>()

    @Synchronized
    override fun save(history: WatchHistory) {
        if (!history.isValid()) return
        val previous = records[recordKey(history.mediaKey, history.episodeKey)]
        if (previous == null || history.updatedAt >= previous.updatedAt) {
            records[recordKey(history.mediaKey, history.episodeKey)] = history
        }
    }

    @Synchronized
    override fun getHistory(): List<WatchHistory> = records.values
        .groupBy { it.mediaKey }
        .values
        .mapNotNull { records -> records.maxByOrNull { it.updatedAt } }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    override fun getEpisodeHistory(mediaKey: String, episodeKey: String): WatchHistory? =
        records[recordKey(mediaKey, episodeKey)]

    @Synchronized
    override fun remove(mediaKeys: Set<String>) {
        records.entries.removeIf { it.value.mediaKey in mediaKeys }
    }

    @Synchronized
    override fun clear() {
        records.clear()
    }
}

internal fun recordKey(mediaKey: String, episodeKey: String): String = "$mediaKey\u0000$episodeKey"

internal fun WatchHistory.isValid(): Boolean =
    mediaKey.isNotBlank() && episodeKey.isNotBlank() &&
        progressMs >= 0 && durationMs >= 0 && updatedAt >= 0
