package com.aiyifan.app.feature.main

import android.app.Application
import android.os.Bundle
import com.aiyifan.app.BuildConfig
import com.aiyifan.app.R
import com.aiyifan.app.core.data.AppGraph
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.google.android.material.tabs.TabLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AnimeTabNavigationTest {
    @Before fun enableOnline() { StartupSession.mode = AppMode.ONLINE }
    @After fun clearOnline() { StartupSession.mode = null }

    @Test
    fun `old download selection restores downloads in the current tab order`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        AppGraph.initialize(activity)
        controller.create(Bundle().apply { putInt("selected_tab", 2) })
        try {
            val tabs = activity.findViewById<TabLayout>(R.id.bottomTabs)
            val expected = buildList {
                add(R.string.tab_home)
                add(R.string.tab_short_drama)
                if (BuildConfig.ANIME_ENABLED) add(R.string.anime_entry)
                add(R.string.download_action)
                add(R.string.tab_mine)
            }.map(activity::getString)
            assertEquals(expected, (0 until tabs.tabCount).map { tabs.getTabAt(it)?.text.toString() })
            assertEquals(TabLayout.MODE_FIXED, tabs.tabMode)
            assertEquals(TabLayout.GRAVITY_FILL, tabs.tabGravity)
            assertEquals(activity.getString(R.string.download_action), tabs.getTabAt(tabs.selectedTabPosition)?.text)
            assertEquals("DownloadLibraryFragment", activity.supportFragmentManager.primaryNavigationFragment?.javaClass?.simpleName)
            tabs.getTabAt(1)?.select()
            assertEquals("ShortDramaFragment", activity.supportFragmentManager.primaryNavigationFragment?.javaClass?.simpleName)
        } finally {
            controller.destroy()
        }
    }
}
