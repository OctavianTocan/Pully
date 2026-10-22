package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RootJourneySettingsTest {
    @Test
    fun realKeyServicesRetryTheSample503AndReopenWithoutPersistingTheOneTimeSecret() = runTest {
        val h = SettingsJourney(RootJourneyScenario.SettingsRetryOnce)
        val keys = h.account()
        assertTrue(keys.keys().isEmpty())
        assertEquals(RootJourneySettings.CREATE_ERROR, assertFailsWith<ServerError> { keys.create(RootJourneySettings.KEY_NAME) }.message)
        assertTrue(keys.keys().isEmpty())
        assertFalse(h.disk.value.orEmpty().contains(RootJourneySettings.KEY_VALUE))

        // New transport/services retain only the actual persisted sample-server state.
        val reopened = h.account()
        val made = reopened.create(RootJourneySettings.KEY_NAME)
        assertEquals(RootJourneySettings.KEY_VALUE, made.first)
        assertEquals(RootJourneySettings.KEY_ID, made.second.id)
        reopened.ui.made = made
        assertTrue(reopened.copy(made.first))
        assertEquals(listOf(RootJourneySettings.KEY_VALUE), h.copied)
        assertFalse(h.disk.value.orEmpty().contains(made.first))
        reopened.close()
        assertNull(reopened.ui.made)
        assertFalse(reopened.copy(made.first))

        val listed = h.account()
        assertEquals(listOf(made.second), listed.keys())
        listed.revoke(made.second.id)
        assertTrue(h.account().keys().isEmpty())
        assertEquals(2, h.methods.count { it == "agent-key-create" })
        assertEquals(1, h.methods.count { it == "agent-key-revoke" })
    }

    @Test
    fun actualStatsStateShowsTheFailureThenDistinctRealRangeRepliesAndKeepsItsCache() = runTest {
        val h = SettingsJourney(RootJourneyScenario.SettingsRetryOnce)
        val preferences = MemorySettingsPreferences()
        val services = SourceStatsServices(h.repo(), preferences, { true }, { true })
        val page = StatsPageState(services, 0)
        page.refresh()
        assertEquals(StatsLoad.Failed, page.load)
        page.retry()
        page.refresh()
        assertEquals(73L, (page.load as StatsLoad.Ready).summary.totals.sessions)
        page.select(1); page.refresh()
        assertEquals(303L, (page.load as StatsLoad.Ready).summary.totals.sessions)
        page.select(2); page.refresh()
        assertEquals(903L, (page.load as StatsLoad.Ready).summary.totals.sessions)
        val reopened = SourceStatsServices(h.repo(), preferences, { true }, { true })
        assertEquals(73L, reopened.cached(7)?.totals?.sessions)
        assertEquals(903L, reopened.cached(90)?.totals?.sessions)
        assertEquals(listOf(7, 7, 30, 90), h.calls.filter { it.first == "analytics-summary" }.map { it.second.getValue("days").jsonPrimitive.int })
        val disabled = SourceStatsServices(h.repo(), preferences, { false }, { true })
        assertNull(disabled.cached(7))
        assertFailsWith<CancellationException> { disabled.fetch(7) }
        assertEquals(4, h.methods.count { it == "analytics-summary" })
    }

    @Test
    fun realFeedbackServicesRetainTheFailedDraftThenReopenBothTypedAndCopiedCodeNotes() = runTest {
        val h = SettingsJourney(RootJourneyScenario.SettingsRetryOnce)
        val feedback = h.feedback()
        feedback.refresh()
        assertEquals(RootJourneySettings.EARLIER_FEEDBACK, feedback.ui.sent?.single()?.text)
        feedback.ui.text = RootJourneySettings.FEEDBACK
        assertEquals(RootJourneySettings.FEEDBACK_ERROR,
            assertFailsWith<ServerError> { feedback.send(feedback.ui.text, "settings", null, false) }.message)
        assertEquals(RootJourneySettings.FEEDBACK, feedback.ui.text)
        assertEquals(1, feedback.ui.sent?.size)
        feedback.send(feedback.ui.text, "settings", null, false)
        assertEquals("", feedback.ui.text)
        assertEquals(RootJourneySettings.FEEDBACK, feedback.ui.sent?.first()?.text)

        val reopened = h.feedback()
        reopened.refresh()
        assertEquals(RootJourneySettings.FEEDBACK, reopened.ui.sent?.first()?.text)
        reopened.ui.text = RootJourneySettings.CODE_FEEDBACK
        reopened.send(reopened.ui.text, "review", "pully/mobile-fixtures/76", false)
        assertEquals(listOf(RootJourneySettings.CODE_FEEDBACK, RootJourneySettings.FEEDBACK, RootJourneySettings.EARLIER_FEEDBACK), reopened.ui.sent?.map { it.text })
        reopened.close()
        assertEquals("", reopened.ui.text)
        assertNull(reopened.ui.sent)
        assertEquals(3, h.methods.count { it == "feedback-send" })
    }

    @Test
    fun theSameModelPortPersistsTheSecondCatalogChoiceAndCanStartOnlyThatBoundedModel() = runTest {
        val h = SettingsJourney()
        val prefs = MemorySettingsPreferences()
        var config = ModelConfiguration(RootJourneyWork.ENGINE, "")
        fun models() = SourceModelSettingsServices(h.repo { config }, prefs, { config }, { config = it }, { true })
        val services = models()
        val catalog = services.fetch(RootJourneyWork.ENGINE).getOrThrow()
        assertEquals(listOf(RootJourneyWork.MODEL, RootJourneyWork.ALTERNATE_MODEL), catalog.models.map { it.id })
        services.remember(config.engine, RootJourneyWork.ALTERNATE_MODEL)
        services.save(config.copy(model = RootJourneyWork.ALTERNATE_MODEL))
        h.settings.updateEffort("high")
        val reopened = models()
        assertEquals(RootJourneyWork.ALTERNATE_MODEL, reopened.configuration().model)
        assertEquals(RootJourneyWork.ALTERNATE_MODEL, reopened.remembered(config.engine))
        assertEquals(catalog, reopened.cached(config.engine))
        val repo = h.repo { config }
        val id = repo.startTask("analyze", RootJourneyTransport.KEY, effort = h.settings.effort) {
            put("locale", "en"); put("then", JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief"))))
        }
        assertEquals(id, repo.tasks(RootJourneyTransport.KEY).single().id)
        val actual = h.calls.single { it.first == "ai-task-start" }.second.getValue("model").jsonObject
        assertEquals(RootJourneyWork.ALTERNATE_MODEL, actual.getValue("model").jsonPrimitive.content)
        assertEquals("high", actual.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun strictSettingsRejectUnknownMetadataArgumentsSecretsAndReadOnlyOrUnauthenticatedWrites() = runTest {
        val h = SettingsJourney(RootJourneyScenario.SettingsRetryOnce)
        val transport = h.transport()
        suspend fun rejected(name: String, args: JsonObject, headers: Map<String, String> = mapOf("X-Pully-Session" to RootJourneyTransport.SESSION)) {
            assertFails { transport.execute(request(name, args, headers)) }
        }
        rejected("agent-key-create", buildJsonObject { put("name", "ordinary agent") })
        rejected("agent-key-create", buildJsonObject { put("name", RootJourneySettings.KEY_NAME); put("token", "ordinary") })
        rejected("agent-key-revoke", buildJsonObject { put("id", "another-accounts-key") })
        rejected("analytics-summary", buildJsonObject { put("days", 365) })
        rejected("analytics-summary", buildJsonObject { put("days", "7") })
        rejected("analytics-summary", buildJsonObject { put("days", 7) }, emptyMap())
        val note = h.report()
        for (invalid in listOf(
            JsonObject(note + ("text" to JsonPrimitive("Ordinary private text"))),
            JsonObject(note + ("endpoint" to JsonPrimitive("https://ordinary.example"))),
            JsonObject(note + ("device" to JsonObject(note.getValue("device").jsonObject + ("identifier" to JsonPrimitive("private-device"))))),
            JsonObject(note + ("place" to JsonPrimitive("another/private/8"))),
        )) rejected("feedback-send", invalid)
        rejected("feedback-list", buildJsonObject { put("limit", 10) })
        rejected("unknown-settings-method", JsonObject(emptyMap()))
        assertFalse(h.disk.value.orEmpty().contains("Ordinary private text"))
        // Rejected input must not consume any of the three deliberate failures.
        assertFailsWith<ServerError> { h.account().create(RootJourneySettings.KEY_NAME) }
        assertFailsWith<ServerError> { h.repo().analyticsSummary(7) }
        assertFailsWith<ServerError> { h.feedback().send(RootJourneySettings.FEEDBACK, "settings", null, false) }
        val readOnly = RootJourneyTransport()
        for (name in RootJourneySettings.OPERATIONS) assertFails {
            readOnly.execute(request(name, JsonObject(emptyMap()), mapOf("X-Pully-Session" to RootJourneyTransport.SESSION)))
        }
        assertFails { transport.execute(RpcRequest("$PREVIEW_SERVER/api/rpc", emptyMap(), """{"name":"account-status","args":null}""")) }
    }

    @Test
    fun corruptOrCrossScenarioSettingsStateCannotBecomeAUsableSampleAccount() = runTest {
        val h = SettingsJourney()
        h.account().keys()
        val raw = Json.parseToJsonElement(assertNotNull(h.disk.value)).jsonObject
        val settings = raw.getValue("settings").jsonObject
        for (invalid in listOf(
            settings + ("keyRevoked" to JsonPrimitive(true)),
            settings + ("createRejected" to JsonPrimitive(true)),
            settings + ("feedback" to JsonArray(listOf(JsonPrimitive("private feedback")))),
            settings + ("feedback" to JsonArray(List(3) { JsonPrimitive(RootJourneySettings.FEEDBACK) })),
            settings + ("unknown" to JsonPrimitive(true)),
        )) {
            val disk = SettingsJourneyStore(JsonObject(raw + ("settings" to JsonObject(invalid))).toString())
            assertFails { RootJourneyTransport.fullJourney(disk) { 1_000L } }
        }
        assertFails { RootJourneyTransport.fullJourney(h.disk, scenario = RootJourneyScenario.SettingsRetryOnce) { 1_000L } }
    }
}

private class SettingsJourney(val scenario: RootJourneyScenario = RootJourneyScenario.Normal) {
    val disk = SettingsJourneyStore()
    val copied = mutableListOf<String>()
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val methods get() = calls.map { it.first }
    val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    }) { "en" }
    fun transport() = RootJourneyTransport.fullJourney(disk, scenario = scenario) { 1_000L }
    fun repo(configuration: () -> ModelConfiguration = { ModelConfiguration(RootJourneyWork.ENGINE, "") }): Repo {
        val transport = transport()
        return Repo({
            val model = configuration()
            RepoConfiguration(PREVIEW_SERVER, mapOf("X-Pully-Session" to RootJourneyTransport.SESSION), model.engine, model.model)
        }, RpcTransport { request ->
            val envelope = Json.parseToJsonElement(request.body).jsonObject
            calls += envelope.getValue("name").jsonPrimitive.content to envelope.getValue("args").jsonObject
            transport.execute(request)
        })
    }
    fun account() = SourceAccountPagesServices(repo(), { RootJourneyTransport.EMAIL }, { true }, {}, copied::add)
    fun feedback() = SourceFeedbackServices(repo(), settings, metadata(), { "settings" }, { null }, { true })
    fun report() = metadata().report(RootJourneySettings.FEEDBACK, "settings", null, false, "en").toJson()
    private fun metadata() = FeedbackMetadata("0.66.0", 83, "Apple", "iPhone", "iOS 26.4.1", ios = "26.4.1")
}

private class SettingsJourneyStore(var value: String? = null) : RootJourneyStateStore {
    override fun read() = value
    override fun write(state: String) { require(state.length <= 32_000); value = state }
}

private fun request(name: String, args: JsonObject, headers: Map<String, String>) = RpcRequest("$PREVIEW_SERVER/api/rpc", headers,
    buildJsonObject { put("name", name); put("args", args) }.toString())
