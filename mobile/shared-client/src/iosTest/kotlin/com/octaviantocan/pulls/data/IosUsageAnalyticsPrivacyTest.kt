package com.octaviantocan.pulls.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IosUsageAnalyticsPrivacyTest {
    @Test
    fun unverifiedAndGuestIntervalsCannotBindQueuesOrUploadAndRetirementSettlesTheOldScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var now = 0L
            var config = RepoConfiguration("https://old.example", mapOf("X-Pully-Session" to "fixture-token"), generation = 1)
            var owner: String? = "reader@example.com"
            var requests = 0
            var cancelled = 0
            val disks = mutableMapOf<String, Disk>()
            val analytics = IosUsageAnalytics(backgroundScope, { config }, { owner }, { true }, "test", IosUsageEnvironment(
                RpcTransport {
                    requests++
                    try { awaitCancellation() } finally { cancelled++ }
                },
                { namespace -> disks.getOrPut(namespace) { Disk() } }, AnalyticsIo { it() }, { now }, { "12345678-1234-1234-1234-123456789abc" },
            ))
            analytics.foreground(); analytics.showScreen("home"); analytics.firstFrame(); runCurrent()
            assertTrue(disks.isEmpty())
            assertEquals(0, requests)
            now = 100_000
            analytics.setPrivateConnection(true); runCurrent()
            val old = disks.values.single()
            assertEquals(1, requests)
            assertEquals("warm", old.events().single { it.name() == "app.open" }.getValue("props").jsonObject.getValue("start").jsonPrimitive.content)
            now = 102_000
            owner = null // AccountRuntime's before-publication invalidation has already removed public email.
            analytics.setPrivateConnection(false); runCurrent()
            assertEquals(1, cancelled)
            assertEquals("2.0", old.events().single { it.name() == "screen.time" }.getValue("props").jsonObject.getValue("seconds").jsonPrimitive.content)
            val retained = old.lines.toList()
            config = RepoConfiguration("https://new.example", generation = 2)
            analytics.showScreen("review"); analytics.track("settings.changed", "key" to "theme"); analytics.foreground(); runCurrent()
            assertEquals(retained, old.lines)
            assertEquals(1, disks.size)
            assertEquals(1, requests)
            analytics.close(); runCurrent()
            assertEquals(retained, old.lines)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun returningFromBackgroundCountsOnlyTheNewVerifiedForegroundInterval() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var now = 0L
            val disk = Disk()
            val analytics = IosUsageAnalytics(backgroundScope, { RepoConfiguration("https://private.example") }, { null }, { true }, "test", IosUsageEnvironment(
                RpcTransport { awaitCancellation() }, { disk }, AnalyticsIo { it() }, { now }, { "12345678-1234-1234-1234-123456789abc" },
            ))
            analytics.foreground(); analytics.setPrivateConnection(true); analytics.showScreen("home"); runCurrent()
            now = 2_000
            analytics.background(); analytics.setPrivateConnection(false); runCurrent()
            now = 100_000
            analytics.foreground(); runCurrent()
            now = 200_000
            analytics.setPrivateConnection(true); runCurrent()
            now = 203_000
            analytics.background(); analytics.setPrivateConnection(false); runCurrent()
            val durations = disk.events().filter { it.name() == "app.background" }.map {
                it.getValue("props").jsonObject.getValue("seconds").jsonPrimitive.content.toLong()
            }
            assertEquals(listOf(2L, 3L), durations)
            val screens = disk.events().filter { it.name() == "screen.time" }.map {
                it.getValue("props").jsonObject.getValue("seconds").jsonPrimitive.content.toDouble()
            }
            assertEquals(listOf(2.0, 3.0), screens)
            analytics.close(); runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private class Disk : AnalyticsDisk {
        var lines = emptyList<String>()
        private val metadata = mutableMapOf<String, String>()
        override fun read() = lines
        override fun replace(lines: List<String>) { this.lines = lines.toList() }
        override fun metadata(name: String) = metadata[name]
        override fun metadata(name: String, value: String?) { if (value == null) metadata.remove(name) else metadata[name] = value }
        fun events() = lines.map { Json.parseToJsonElement(it).jsonObject }
    }
}
