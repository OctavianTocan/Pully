package com.octaviantocan.pulls.settings

import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDefaultsSettingsTest {
    @Test
    fun nativeStoragePreservesMissingDefaultsExplicitFalseSetsAndFloatChoices() {
        val suite = "pully-shared-settings-tests"
        val defaults = NSUserDefaults(suiteName = suite)
        defaults.removePersistentDomainForName(suite)
        try {
            val preferences = UserDefaultsSettings(defaults)
            assertFalse(preferences.contains("sounds"))
            assertTrue(preferences.getBoolean("sounds", true))
            assertEquals(1f, preferences.getFloat("codeZoom", 1f))
            val edit = preferences.edit().putBoolean("sounds", false).putFloat("codeZoom", 1.3f)
                .putString("layout", "UNIFIED").putStringSet("hiddenRepos", setOf("org/b", "org/a"))
            assertFalse(preferences.contains("sounds"))
            edit.apply()

            val reloaded = UserDefaultsSettings(defaults)
            assertTrue(reloaded.contains("sounds"))
            assertFalse(reloaded.getBoolean("sounds", true))
            assertEquals(1.3f, reloaded.getFloat("codeZoom", 1f))
            assertEquals("UNIFIED", reloaded.getString("layout", null))
            assertEquals(setOf("org/a", "org/b"), reloaded.getStringSet("hiddenRepos", null))
            assertEquals("UNIFIED", defaults.stringForKey("pulls-ui.layout"))
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }
}
