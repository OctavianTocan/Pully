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
    fun sendsAPasswordOrSessionOnlyOverHttpsOrToThisMachine() {
        assertTrue(carriesSecrets("https://prs.example.com"))
        assertTrue(carriesSecrets("http://10.0.2.2:5173"))
        assertTrue(carriesSecrets("http://localhost:5173"))
        assertFalse(carriesSecrets("http://prs.example.com"))
        assertFalse(carriesSecrets("http://192.168.1.4:5173"))
        assertFalse(carriesSecrets("not a url"))
    }

    @Test
    fun readsTheAccountsKeysWithoutTheKeyItself() {
        val key = Json.parseToJsonElement("""{"id":"k1","name":"Claude Code","hint":"pully_AbCd","createdAt":"2026-10-16T12:00:00Z"}""").jsonObject.asAgentKey()
        assertEquals(AgentKey("k1", "Claude Code", "pully_AbCd", "2026-10-16T12:00:00Z", null), key)
    }

    @Test
    fun readsAPostedRow() {
        val row = Json.parseToJsonElement(
            """{"id":"a1B2c3D4e5F6","title":"Retry","author":"Laptop","version":2,"updatedAt":"2026-10-16T12:00:00Z","files":2,"additions":4,"deletions":1}""",
        ).jsonObject.asPostedRow()
        assertEquals(PostedRow("a1B2c3D4e5F6", "Retry", "Laptop", 2, "2026-10-16T12:00:00Z", 2, 4, 1), row)
    }

    @Test
    fun readsSyncedSettingsAndSkipsOnesWithoutATime() {
        val values = Json.parseToJsonElement(
            """{"values":{"theme":{"value":"dark","at":10},"sounds":{"value":false,"at":20},"broken":{"value":1}}}""",
        ).jsonObject.asSyncedSettings()
        assertEquals(mapOf("theme" to SyncedSetting(JsonPrimitive("dark"), 10), "sounds" to SyncedSetting(JsonPrimitive(false), 20)), values)
    }

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
