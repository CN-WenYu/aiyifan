package com.aiyifan.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceStoreTest {

    @Test
    fun `missing preference uses dark mode`() {
        assertEquals(ThemeMode.DARK, ThemePreferenceStore.resolve(null))
    }

    @Test
    fun `invalid preference uses dark mode`() {
        assertEquals(ThemeMode.DARK, ThemePreferenceStore.resolve("unsupported"))
    }

    @Test fun `explicit user choices override default`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemePreferenceStore.resolve(mode.storageValue))
        }
    }
}
