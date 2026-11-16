package com.aiyifan.app.feature.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class PreparationStatus { ADDED, EXISTING, FAILED }

data class DownloadPreparation(val id: String, val status: PreparationStatus, val error: Exception? = null)

/** Preparation belongs to the visible picker; accepted downloads belong to the service. */
object DownloadBatch {
    suspend fun prepare(
        ids: List<String>,
        exists: (String) -> Boolean,
        enqueue: suspend (String) -> Unit,
        onPrepared: (DownloadPreparation) -> Unit,
    ) {
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            var failure: Exception? = null
            val status = try {
                if (exists(id)) PreparationStatus.EXISTING else {
                    enqueue(id)
                    PreparationStatus.ADDED
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
                PreparationStatus.FAILED
            }
            onPrepared(DownloadPreparation(id, status, failure))
        }
    }
}
