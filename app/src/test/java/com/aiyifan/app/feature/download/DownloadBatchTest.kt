package com.aiyifan.app.feature.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadBatchTest {
    @Test
    fun `batch retains accepted items and reports individual preparation failures`() = runBlocking {
        val accepted = mutableListOf<String>()
        val reports = mutableListOf<DownloadPreparation>()
        DownloadBatch.prepare(listOf("one", "bad", "existing", "last"), { it == "existing" }, {
            if (it == "bad") throw IllegalStateException("unavailable")
            accepted += it
        }, reports::add)
        assertEquals(listOf("one", "last"), accepted)
        assertEquals(listOf(PreparationStatus.ADDED, PreparationStatus.FAILED, PreparationStatus.EXISTING, PreparationStatus.ADDED), reports.map { it.status })
    }

    @Test
    fun `cancelling preparation leaves accepted tasks and does not prepare later episodes`() = runBlocking {
        val accepted = mutableListOf<String>()
        val reports = mutableListOf<DownloadPreparation>()
        try {
            DownloadBatch.prepare(listOf("one", "cancel", "later"), { false }, {
                if (it == "cancel") throw CancellationException()
                accepted += it
            }, reports::add)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("one"), accepted)
        assertEquals(1, reports.size)
    }

    @Test
    fun `empty selection does not prepare or report any task`() = runBlocking {
        DownloadBatch.prepare(emptyList(), { error("No existing lookup") }, { error("No enqueue") }, { error("No report") })
    }
}
