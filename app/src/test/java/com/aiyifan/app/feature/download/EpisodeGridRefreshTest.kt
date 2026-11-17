package com.aiyifan.app.feature.download

import androidx.recyclerview.widget.RecyclerView
import com.aiyifan.app.R
import com.aiyifan.app.core.model.Episode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class EpisodeGridRefreshTest {
    @Test fun `selecting one episode notifies only its row and retains selection snapshots`() {
        val selected = linkedSetOf<String>()
        val adapter = adapter()
        adapter.submitState(selected, emptyMap(), true)
        val observer = Changes().also(adapter::registerAdapterDataObserver)
        selected.add("ep-2")
        adapter.submitState(selected, emptyMap(), true)
        assertEquals(listOf(1), observer.positions)
        assertEquals(0, observer.fullRefreshes)
        observer.positions.clear()
        selected.clear()
        adapter.submitState(selected, emptyMap(), true)
        assertEquals(listOf(1), observer.positions)
    }

    @Test fun `equivalent task updates do not refresh episodes`() {
        val adapter = adapter()
        adapter.submitState(setOf("ep-2"), mapOf("ep-1" to R.string.download_episode_added), true)
        val observer = Changes().also(adapter::registerAdapterDataObserver)
        repeat(3) { adapter.submitState(setOf("ep-2"), mapOf("ep-1" to R.string.download_episode_added), true) }
        assertTrue(observer.positions.isEmpty())
        assertEquals(0, observer.fullRefreshes)
        adapter.submitState(setOf("ep-2"), mapOf("ep-1" to R.string.download_completed), true)
        assertEquals(listOf(0), observer.positions)
    }

    @Test fun `select all skips downloaded episodes`() {
        val adapter = adapter()
        val existing = mapOf("ep-1" to R.string.download_completed)
        adapter.submitState(emptySet(), existing, true)
        val observer = Changes().also(adapter::registerAdapterDataObserver)
        adapter.submitState(setOf("ep-1", "ep-2", "ep-3"), existing, true)
        assertEquals(listOf(1, 2), observer.positions)
        assertEquals(0, observer.fullRefreshes)
    }

    private fun adapter() = DownloadSelectionDialog.EpisodeGridAdapter(
        (1..3).map { Episode("ep-$it", "第 $it 集", it, null) },
    ) { }

    private class Changes : RecyclerView.AdapterDataObserver() {
        var fullRefreshes = 0
        val positions = mutableListOf<Int>()
        override fun onChanged() { fullRefreshes++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            positions.addAll(positionStart until positionStart + itemCount)
        }
    }
}
