package com.octaviantocan.pulls.settings

import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun newPreferencesKeepExistingDefaultsWithoutMarkingAnyChoiceAsLocal() {
        val preferences = MemorySettingsPreferences()
        val effects = RecordedSettingsEffects()
        val settings = AppSettings(preferences, effects) { "es" }
        assertEquals("system", settings.theme)
        assertEquals("pulls", settings.colorTheme)
        assertEquals("es", settings.locale)
        assertEquals("split", settings.layout)
        assertEquals("waiting", settings.inboxFilter)
        assertEquals(AnalyzeChoice.of(AnalyzePreset.Standard), settings.analyzeChoice)
        assertEquals(1f, settings.codeZoom)
        assertFalse(settings.codeWrap)
        assertTrue(settings.autoRefresh)
        assertTrue(settings.showThreads)
        assertTrue(settings.sounds)
        assertTrue(settings.haptics)
        assertTrue(settings.usageStats)
        assertTrue(settings.updateNotifications)
        assertEquals(emptySet(), settings.hiddenRepos)
        assertFalse(settings.chosen("theme"))
        assertEquals(emptyList(), effects.events)
    }

    @Test
    fun existingPreferenceKeysAndEncodingsReloadWithoutMigration() {
        val preferences = MemorySettingsPreferences().apply {
            edit().putString("theme", "dark").putString("locale", "fr")
                .putString("layout", "UNIFIED").putString("inboxFilter", "authored")
                .putString("analyzeChoice", "custom:brief,review").putString("appFont", "mona-sans")
                .putString("codeFont", "fira-code").putFloat("codeZoom", 1.3f)
                .putBoolean("sounds", false).putStringSet("hiddenRepos", setOf("org/private"))
                .apply()
        }
        val settings = AppSettings(preferences, RecordedSettingsEffects()) { error("Saved locale must win") }
        assertEquals("dark", settings.theme)
        assertEquals("fr", settings.locale)
        assertEquals("unified", settings.layout)
        assertEquals("authored", settings.inboxFilter)
        assertEquals(AnalyzeChoice(null, tour = false, brief = true, review = true), settings.analyzeChoice)
        assertEquals("mona-sans", settings.appFont)
        assertEquals("fira-code", settings.codeFont)
        assertEquals(1.3f, settings.codeZoom)
        assertFalse(settings.sounds)
        assertEquals(setOf("org/private"), settings.hiddenRepos)
        assertTrue(settings.chosen("theme"))
    }

    @Test
    fun localEditsPersistAndNotifySyncAfterStorageHasTheNewValue() {
        val preferences = MemorySettingsPreferences()
        val events = mutableListOf<String>()
        val effects = object : SettingsEffects {
            override fun touched(name: String) {
                assertTrue(preferences.contains(name), "A touched setting must already be persisted")
                events += "touched:$name"
            }
            override fun settingsChanged(key: String, value: String) { events += "$key:$value" }
            override fun usageStatsEnabled(enabled: Boolean) { events += "usage:$enabled" }
        }
        val settings = AppSettings(preferences, effects) { "en" }
        settings.updateTheme("dark")
        settings.updateLayout("unified")
        settings.updateSounds(false)
        settings.updateEffort("")
        assertEquals(
            listOf("theme:dark", "touched:theme", "layout:unified", "touched:layout", "sounds:off", "touched:sounds", "effort:default", "touched:effort"),
            events,
        )
        assertEquals("UNIFIED", preferences.getString("layout", null))
        val reloaded = AppSettings(preferences, RecordedSettingsEffects()) { "en" }
        assertEquals("dark", reloaded.theme)
        assertEquals("unified", reloaded.layout)
        assertFalse(reloaded.sounds)
    }

    @Test
    fun zoomPreviewIsTransientAndOnlyTheCommittedClampedChoiceSurvivesReload() {
        val preferences = MemorySettingsPreferences()
        val effects = RecordedSettingsEffects()
        val settings = AppSettings(preferences, effects) { "en" }
        settings.previewCodeZoom(8f)
        assertEquals(AppSettings.MAX_ZOOM, settings.codeZoom)
        assertFalse(settings.chosen("codeZoom"))
        assertEquals(1f, AppSettings(preferences, RecordedSettingsEffects()) { "en" }.codeZoom)
        assertEquals(emptyList(), effects.events)
        settings.updateCodeZoom(-4f)
        assertEquals(AppSettings.MIN_ZOOM, AppSettings(preferences, RecordedSettingsEffects()) { "en" }.codeZoom)
        assertEquals(listOf("touched:codeZoom"), effects.events)
    }

    @Test
    fun bulkRepositoryVisibilityIsIdempotentAndItsSyncedJsonRemainsSorted() {
        val preferences = MemorySettingsPreferences()
        val effects = RecordedSettingsEffects()
        val settings = AppSettings(preferences, effects) { "en" }
        settings.setReposShown(listOf("org/z", "org/a", "org/z"), false)
        settings.setReposShown(listOf("org/z", "org/a"), false)
        assertEquals(setOf("org/a", "org/z"), settings.hiddenRepos)
        assertEquals(JsonArray(listOf(JsonPrimitive("org/a"), JsonPrimitive("org/z"))), settings.syncedValue("hiddenRepos"))
        assertEquals(listOf("touched:hiddenRepos"), effects.events)
        settings.setRepoShown("org/a", true)
        assertEquals(setOf("org/z"), AppSettings(preferences, RecordedSettingsEffects()) { "en" }.hiddenRepos)
        settings.updateInboxFilter("unknown")
        settings.updateInboxFilter("waiting")
        settings.updateInboxFilter("all")
        assertEquals("all", AppSettings(preferences, RecordedSettingsEffects()) { "en" }.inboxFilter)
        assertEquals(listOf("touched:hiddenRepos", "touched:hiddenRepos", "touched:inboxFilter"), effects.events)
    }

    @Test
    fun remoteSettingsApplyWithoutBecomingNewLocalEditsAndRejectUnsupportedValues() {
        val preferences = MemorySettingsPreferences()
        val effects = RecordedSettingsEffects()
        val settings = AppSettings(preferences, effects) { "en" }
        assertTrue(settings.applySynced("theme", JsonPrimitive("dark")))
        assertTrue(settings.applySynced("layout", JsonPrimitive("unified")))
        assertTrue(settings.applySynced("codeZoom", JsonPrimitive(8f)))
        assertTrue(settings.applySynced("hiddenRepos", JsonArray(listOf(JsonPrimitive("org/a")))))
        assertTrue(settings.applySynced("inboxFilter", JsonPrimitive("all")))
        for ((name, value) in listOf(
            "theme" to JsonPrimitive("unknown"), "locale" to JsonPrimitive("xx"),
            "layout" to JsonPrimitive("stacked"), "codeZoom" to JsonPrimitive("1.5"),
            "sounds" to JsonPrimitive("false"), "hiddenRepos" to JsonObject(emptyMap()),
            "inboxFilter" to JsonPrimitive("unknown"), "usageStats" to JsonPrimitive(false),
        )) assertFalse(settings.applySynced(name, value), name)
        val reloaded = AppSettings(preferences, RecordedSettingsEffects()) { "en" }
        assertEquals("dark", reloaded.theme)
        assertEquals("unified", reloaded.layout)
        assertEquals(AppSettings.MAX_ZOOM, reloaded.codeZoom)
        assertEquals(setOf("org/a"), reloaded.hiddenRepos)
        assertEquals("all", reloaded.inboxFilter)
        assertTrue(reloaded.sounds)
        assertEquals(emptyList(), effects.events)
    }

    @Test
    fun usageOptOutHappensImmediatelyWithoutReportingItOrSyncingLocalOnlyChoices() {
        val effects = RecordedSettingsEffects()
        val settings = AppSettings(MemorySettingsPreferences(), effects) { "en" }
        settings.updateUsageStats(false)
        assertFalse(settings.usageStats)
        assertEquals(listOf("usage:false"), effects.events)
        settings.updateUsageStats(true)
        assertEquals(listOf("usage:false", "usage:true", "usageStats:on"), effects.events)
        settings.updateUpdateNotifications(false)
        assertEquals("updateNotifications:off", effects.events.last())
        assertNull(settings.syncedValue("usageStats"))
        assertNull(settings.syncedValue("updateNotifications"))
        assertNull(settings.syncedValue("server"))
    }
}

private class RecordedSettingsEffects : SettingsEffects {
    val events = mutableListOf<String>()
    override fun touched(name: String) { events += "touched:$name" }
    override fun settingsChanged(key: String, value: String) { events += "$key:$value" }
    override fun usageStatsEnabled(enabled: Boolean) { events += "usage:$enabled" }
}

private class MemorySettingsPreferences : SettingsPreferences {
    private val values = mutableMapOf<String, Any>()
    override fun getString(name: String, default: String?): String? = values[name] as? String ?: default
    override fun getBoolean(name: String, default: Boolean): Boolean = values[name] as? Boolean ?: default
    override fun getFloat(name: String, default: Float): Float = values[name] as? Float ?: default
    override fun getStringSet(name: String, default: Set<String>?): Set<String>? =
        (values[name] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: default
    override fun contains(name: String): Boolean = name in values
    override fun edit(): SettingsPreferences.Editor = Editor()

    private inner class Editor : SettingsPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(name: String, value: String?) = apply { pending[name] = value }
        override fun putBoolean(name: String, value: Boolean) = apply { pending[name] = value }
        override fun putFloat(name: String, value: Float) = apply { pending[name] = value }
        override fun putStringSet(name: String, value: Set<String>) = apply { pending[name] = value.toSet() }
        override fun apply() {
            for ((name, value) in pending) if (value == null) values.remove(name) else values[name] = value
            pending.clear()
        }
    }
}
