package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    @Test
    fun takesOnlyTheSettingsTheAccountChangedLater() {
        val remote = mapOf(
            "theme" to SyncedSetting(JsonPrimitive("dark"), 30),
            "sounds" to SyncedSetting(JsonPrimitive(false), 5),
            "codeZoom" to SyncedSetting(JsonPrimitive(1.16f), 40),
            // A setting this phone keeps to itself is never taken from the account.
            "usageStats" to SyncedSetting(JsonPrimitive(false), 99),
        )
        assertEquals(listOf("codeZoom", "theme"), SettingsSync.newer(mapOf("theme" to 20L, "sounds" to 10L), remote))
        assertEquals(emptyList(), SettingsSync.newer(mapOf("theme" to 30L, "sounds" to 5L, "codeZoom" to 40L), remote))
        assertNull(SettingsSync.SYNCED.firstOrNull { it == "usageStats" || it == "updateNotifications" })
    }

    @Test
    fun settingsFromAnotherAccountGiveWayToTheNewOnes() {
        val times = mapOf("theme" to 200L, "sounds" to 0L, "codeZoom" to 1L)
        assertEquals(mapOf("theme" to 1L, "codeZoom" to 1L), SettingsSync.disowned("ana@octaviantocan.com", times))
        assertEquals(emptyMap(), SettingsSync.disowned(null, times))
    }
}
