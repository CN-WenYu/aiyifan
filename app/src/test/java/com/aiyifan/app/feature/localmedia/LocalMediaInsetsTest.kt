package com.aiyifan.app.feature.localmedia

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiyifan.app.R
import com.aiyifan.app.core.startup.AppMode
import com.aiyifan.app.core.startup.StartupSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalMediaInsetsTest {
    @Test fun `title and content own system insets without shrinking the fragment container`() {
        StartupSession.mode = AppMode.LOCAL
        val controller = Robolectric.buildActivity(LocalMediaActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        try {
            val activity = controller.setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val container = activity.findViewById<ViewGroup>(R.id.localMediaContainer)
            val fragment = activity.supportFragmentManager.fragments.single().requireView() as ViewGroup
            val header = fragment.getChildAt(0)
            val initialHeaderHeight = header.layoutParams.height
            val list = fragment.findViewById<android.view.View>(R.id.localRecycler)
            val initialBottom = list.paddingBottom
            val bars = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 32, 0, 48)).build()
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(container, bars) }
            assertEquals(0, container.paddingTop)
            assertEquals(0, container.paddingBottom)
            assertEquals(32, header.paddingTop)
            assertEquals(initialHeaderHeight + 32, header.layoutParams.height)
            assertEquals(initialBottom + 48, list.paddingBottom)
        } finally {
            controller.pause().stop().destroy()
            StartupSession.mode = null
        }
    }
}
