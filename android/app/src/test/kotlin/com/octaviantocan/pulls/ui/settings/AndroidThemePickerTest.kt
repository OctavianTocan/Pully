package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidThemePickerTest {
    @Test
    fun bothActualHostBindingsKeepTheirOpeningEntryAcrossReplacementAndReturn() = runTest {
        for (mode in NativeEntryMode.entries) {
            val h = Harness()
            val entry = NativeEntryState(true, mode)
            val drawn = captureAndroidColorThemePicker(h.settings, entry)
            entry.select(if (mode == NativeEntryMode.Explore) NativeEntryMode.ExistingServer else NativeEntryMode.Explore)
            entry.select(mode)
            drawn.choose(backgroundScope, h.target, dark = false, motion = false)
            runCurrent()
            h.assertUnchanged()
        }
    }

    @Test
    fun aQueuedAndroidChoiceCannotTouchTheReplacementSourceAndANewPickerStillWorks() = runTest {
        val h = Harness()
        val entry = NativeEntryState(true, NativeEntryMode.ExistingServer)
        val drawn = captureAndroidColorThemePicker(h.settings, entry)
        drawn.choose(backgroundScope, h.target, dark = false, motion = false)
        RepoIdentity.invalidate()
        runCurrent()
        h.assertUnchanged()
        captureAndroidColorThemePicker(h.settings, entry).choose(backgroundScope, h.target, dark = false, motion = false)
        runCurrent()
        assertEquals(h.target.id, h.preferences.getString("colorTheme", null))
        assertEquals(listOf("changed:colorTheme", "sync:colorTheme"), h.effects)
    }

    private class Harness {
        val preferences = ThemePreferences()
        val effects = mutableListOf<String>()
        val settings = AppSettings(preferences, object : SettingsEffects {
            override fun touched(name: String) { effects += "sync:$name" }
            override fun settingsChanged(key: String, value: String) { effects += "changed:$key" }
            override fun usageStatsEnabled(enabled: Boolean) {}
        }) { "en" }
        private val initial = settings.colorTheme
        val target = ColorThemes.all.first { it.id != initial }
        fun assertUnchanged() {
            assertEquals(initial, settings.colorTheme)
            assertNull(preferences.getString("colorTheme", null))
            assertTrue(effects.isEmpty())
        }
    }

    private class ThemePreferences : SettingsPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getString(name: String, default: String?) = values[name] as? String ?: default
        override fun getBoolean(name: String, default: Boolean) = values[name] as? Boolean ?: default
        override fun getFloat(name: String, default: Float) = values[name] as? Float ?: default
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(name: String, default: Set<String>?) = values[name] as? Set<String> ?: default
        override fun contains(name: String) = name in values
        override fun edit() = object : SettingsPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(name: String, value: String?) = apply { pending[name] = value }
            override fun putBoolean(name: String, value: Boolean) = apply { pending[name] = value }
            override fun putFloat(name: String, value: Float) = apply { pending[name] = value }
            override fun putStringSet(name: String, value: Set<String>) = apply { pending[name] = value }
            override fun apply() { values.putAll(pending) }
        }
    }
}
