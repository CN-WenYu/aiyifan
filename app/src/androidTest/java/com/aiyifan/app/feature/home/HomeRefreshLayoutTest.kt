package com.aiyifan.app.feature.home

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiyifan.app.R
import com.aiyifan.app.feature.main.MainActivity
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.core.model.WatchHistory
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRefreshLayoutTest {

    @Before
    fun prepareOnlineSession() { StartupSession.mode = AppMode.ONLINE }

    @After
    fun resetSession() { StartupSession.mode = null }

    @Test
    fun homeExposesPullToRefreshContainer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<SwipeRefreshLayout>(R.id.homeRefresh))
            }
        }
    }

    @Test
    fun offlineStateShowsNetworkMessageAndRetryBelowExistingHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var retries = 0
                val status = HomeStatusAdapter { retries++ }
                val history = ContinueWatchingAdapter({}, {}, {})
                val feed = ConcatAdapter(history, status)
                history.submitRecords(listOf(WatchHistory("real", "episode", "已观看影片", "", 1, 1000, 9000, 1)))
                status.submitStatus(HomeStatus.OFFLINE)
                assertEquals(2, feed.itemCount)
                assertEquals(history, feed.adapters.first())
                val holder = status.onCreateViewHolder(FrameLayout(activity), 0)
                status.onBindViewHolder(holder, 0)
                assertEquals("暂无网络，请检查网络连接", holder.itemView.findViewById<TextView>(R.id.statusMessage).text.toString())
                assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.networkIcon).visibility)
                assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.loadingIndicator).visibility)
                holder.itemView.findViewById<View>(R.id.retryButton).performClick()
                assertEquals(1, retries)
                history.submitRecords(emptyList())
                assertEquals(1, feed.itemCount)
            }
        }
    }

    @Test
    fun loadingEmptyAndServiceFailureHaveDistinctStates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val adapter = HomeStatusAdapter {}
                val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
                adapter.onBindViewHolder(holder, 0)
                assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.loadingIndicator).visibility)
                assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.retryButton).visibility)
                adapter.submitStatus(HomeStatus.EMPTY)
                adapter.onBindViewHolder(holder, 0)
                assertEquals("暂无内容", holder.itemView.findViewById<TextView>(R.id.statusMessage).text.toString())
                assertFalse(holder.itemView.findViewById<View>(R.id.statusMessage).isClickable)
                adapter.submitStatus(HomeStatus.ERROR)
                adapter.onBindViewHolder(holder, 0)
                assertEquals("加载失败，点击重试", holder.itemView.findViewById<TextView>(R.id.statusMessage).text.toString())
                assertTrue(holder.itemView.findViewById<View>(R.id.statusMessage).isClickable)
                assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.networkIcon).visibility)
                adapter.submitStatus(HomeStatus.CONTENT)
                assertEquals(0, adapter.itemCount)
            }
        }
    }
}
