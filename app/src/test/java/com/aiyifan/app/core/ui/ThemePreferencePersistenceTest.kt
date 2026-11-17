package com.aiyifan.app.core.ui

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ThemePreferencePersistenceTest {
    @Test fun `fresh install is dark and manual changes survive reopening`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val previousNightMode = AppCompatDelegate.getDefaultNightMode()
        preferences.edit().clear().commit()
        try {
            val store = ThemePreferenceStore(context)
            store.applyCurrentMode()
            assertEquals(ThemeMode.DARK, store.currentMode())
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
            ThemeMode.entries.forEach { mode ->
                store.select(mode)
                val reopened = ThemePreferenceStore(context)
                assertEquals(mode, reopened.currentMode())
                reopened.applyCurrentMode()
                assertEquals(mode.nightMode, AppCompatDelegate.getDefaultNightMode())
            }
        } finally {
            preferences.edit().clear().commit()
            AppCompatDelegate.setDefaultNightMode(previousNightMode)
        }
    }
}
