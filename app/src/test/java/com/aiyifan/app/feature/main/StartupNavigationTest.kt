package com.aiyifan.app.feature.main

import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import com.aiyifan.app.feature.startup.StartupActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StartupNavigationTest {
    @Test fun `online root cannot restore without a fresh process decision`() {
        for (mode in listOf(null, AppMode.LOCAL)) {
            StartupSession.mode = mode
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            controller.get().setTheme(R.style.AppTheme)
            try {
                val activity = controller.create(Bundle()).get()
                val next = shadowOf(activity).nextStartedActivity
                assertEquals(StartupActivity::class.java.name, next.component!!.className)
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, next.flags)
                assertTrue(activity.isFinishing)
                assertNull(activity.findViewById<android.view.View>(R.id.fragmentContainer))
            } finally {
                controller.destroy()
                StartupSession.mode = null
            }
        }
    }

    @Test fun `restored top activities cannot bypass the startup check`() {
        val activities = listOf(
            com.aiyifan.app.feature.video.VideoPlayerActivity::class.java,
            com.aiyifan.app.feature.search.SearchActivity::class.java,
            com.aiyifan.app.feature.history.HistoryActivity::class.java,
            com.aiyifan.app.feature.collection.CollectionActivity::class.java,
            com.aiyifan.app.feature.download.DownloadsActivity::class.java,
            com.aiyifan.app.feature.localmedia.LocalVideoPlayerActivity::class.java,
        )
        StartupSession.mode = null
        for (type in activities) {
            val controller = Robolectric.buildActivity(type)
            controller.get().setTheme(R.style.AppTheme)
            com.aiyifan.app.core.data.AppGraph.initialize(controller.get())
            try {
                val activity = controller.create(Bundle()).start().resume().get()
                val next = shadowOf(activity).nextStartedActivity
                assertEquals(type.simpleName, StartupActivity::class.java.name, next?.component?.className)
                assertTrue(activity.isFinishing)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }
}
