package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.settings.applyColorTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEntryThemeTest {
    @Test
    fun queuedThemeChoiceCannotEditTheReplacementWorkspaceBeforeItsOldCompositionDisposes() = runTest {
        val preferences = MemorySettingsPreferences()
        val writes = mutableListOf<String>()
        val settings = AppSettings(preferences, object : SettingsEffects {
            override fun touched(name: String) { writes += name }
            override fun settingsChanged(key: String, value: String) {}
            override fun usageStatsEnabled(enabled: Boolean) {}
        }) { "en" }
        val original = settings.colorTheme
        val theme = ColorThemes.all.first { it.id != original }
        var current = true
        backgroundScope.applyColorTheme(settings, theme, dark = false, motion = false, eligible = { current })
        current = false
        runCurrent()
        assertEquals(original, settings.colorTheme)
        assertEquals(emptyList(), writes)
        current = true
        backgroundScope.applyColorTheme(settings, theme, dark = false, motion = false, eligible = { current })
        runCurrent()
        assertEquals(theme.id, settings.colorTheme)
        assertEquals(listOf("colorTheme"), writes)
    }
}
