package com.aiyifan.app.feature.home

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.data.CatalogRepository
import com.aiyifan.app.core.data.FakeCatalogRepository
import com.aiyifan.app.core.data.HomeVideoPage
import com.aiyifan.app.core.model.Category
import com.aiyifan.app.core.model.VideoSummary
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HomeResumeLifecycleTest {
    @Test
    fun `returning online preserves loaded pages instead of loading page one again`() {
        val context = RuntimeEnvironment.getApplication()
        AppGraph.initialize(context)
        var firstPageCalls = 0
        val category = Category("3", "电影", 1, 0)
        val catalog = object : CatalogRepository by FakeCatalogRepository() {
            override suspend fun getCategories() = listOf(category)
            override suspend fun getHomeVideoPage(category: Category, page: Int, size: Int): HomeVideoPage {
                if (page == 1) firstPageCalls++
                return HomeVideoPage((1..8).map { VideoSummary("$page-$it", title = "影片", coverUrl = "", videoType = 1) }, true)
            }
        }
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        try {
            val activity = controller.setup().get()
            val manager = activity.getSystemService(ConnectivityManager::class.java)
            shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
            ))
            val capabilities = NetworkCapabilities()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            val fragment = HomeFragment()
            HomeFragment::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(fragment, catalog)
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val pagination = field(fragment, "pagination") as HomeFeedPagination
            val page = pagination.beginNextPage()!!
            HomeFragment::class.java.getDeclaredMethod("loadNextPage", Category::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(fragment, category, page, field(fragment, "homeRequestVersion"))
            shadowOf(Looper.getMainLooper()).idle()
            val before = pagination.next()
            assertEquals(16, before.size)

            controller.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, firstPageCalls)
            assertEquals(before, pagination.next())
            assertTrue(pagination.hasMore)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `returning online restarts an initial page request canceled while stopped`() {
        AppGraph.initialize(RuntimeEnvironment.getApplication())
        var calls = 0
        val pending = CompletableDeferred<HomeVideoPage>()
        val category = Category("3", "电影", 1, 0)
        val catalog = object : CatalogRepository by FakeCatalogRepository() {
            override suspend fun getCategories() = listOf(category)
            override suspend fun getHomeVideoPage(category: Category, page: Int, size: Int): HomeVideoPage {
                calls++
                return if (calls == 1) pending.await() else HomeVideoPage(
                    listOf(VideoSummary("resumed", title = "影片", coverUrl = "", videoType = 1)), false,
                )
            }
        }
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        try {
            val activity = controller.setup().get()
            val manager = activity.getSystemService(ConnectivityManager::class.java)
            shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
            ))
            val capabilities = NetworkCapabilities()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            val fragment = HomeFragment()
            HomeFragment::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(fragment, catalog)
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, calls)
            assertEquals(HomeStatus.LOADING, (field(fragment, "statusAdapter") as HomeStatusAdapter).status)

            controller.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(2, calls)
            assertEquals(HomeStatus.CONTENT, (field(fragment, "statusAdapter") as HomeStatusAdapter).status)
            assertEquals(listOf("resumed"), (field(fragment, "pagination") as HomeFeedPagination).next().map { it.mediaKey })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun field(fragment: HomeFragment, name: String): Any? = HomeFragment::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(fragment)
}
