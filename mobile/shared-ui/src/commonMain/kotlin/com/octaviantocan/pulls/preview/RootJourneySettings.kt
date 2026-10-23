package com.octaviantocan.pulls.preview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Fixed sample account state, serialized only in the full Debug journey's UUID-isolated store. */
@Serializable
internal class RootJourneySettings {
    private var keyCreated = false
    private var keyRevoked = false
    private var createRejected = false
    private var statsRejected = false
    private var feedbackRejected = false
    private val feedback = mutableListOf<String>()

    fun validate(retryScenario: Boolean) {
        require(!keyRevoked || keyCreated)
        require(retryScenario || !createRejected && !statsRejected && !feedbackRejected)
        require(feedback.size <= FEEDBACK_TEXTS.size && feedback.distinct().size == feedback.size && feedback.all { it in FEEDBACK_TEXTS })
    }

    fun answer(name: String, args: JsonObject, retryScenario: Boolean): JsonElement = when (name) {
        "agent-keys" -> {
            args.exact()
            JsonArray(if (keyCreated && !keyRevoked) listOf(keyItem()) else emptyList())
        }
        "agent-key-create" -> {
            args.exact("name")
            require(args.string("name") == KEY_NAME && !keyCreated) { "Use the fixed sample agent name once." }
            if (retryScenario && !createRejected) {
                createRejected = true
                throw RootJourneySettingsRejected(CREATE_ERROR)
            }
            keyCreated = true
            buildJsonObject { put("key", KEY_VALUE); put("item", keyItem()) }
        }
        "agent-key-revoke" -> {
            args.exact("id")
            require(args.string("id") == KEY_ID && keyCreated && !keyRevoked) { "The sample key is not active." }
            keyRevoked = true
            JsonObject(emptyMap())
        }
        "analytics-summary" -> {
            args.exact("days")
            val days = (args["days"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            require(days in setOf(7, 30, 90)) { "Use an existing Stats range." }
            if (retryScenario && !statsRejected) {
                statsRejected = true
                throw RootJourneySettingsRejected(STATS_ERROR)
            }
            stats(requireNotNull(days))
        }
        "feedback-list" -> {
            args.exact()
            buildJsonObject { put("items", JsonArray(feedback.asReversed().mapIndexed { index, text ->
                feedbackItem("fixture-feedback-$index", text, "open")
            } + feedbackItem("fixture-feedback-earlier", EARLIER_FEEDBACK, "done"))) }
        }
        "feedback-send" -> {
            validateFeedback(args)
            val text = args.string("text")
            require(text !in feedback) { "This sample note has already been sent." }
            if (retryScenario && !feedbackRejected) {
                feedbackRejected = true
                throw RootJourneySettingsRejected(FEEDBACK_ERROR)
            }
            feedback += text
            JsonObject(emptyMap())
        }
        else -> error("Unsupported sample Settings operation.")
    }

    private fun keyItem() = buildJsonObject {
        put("id", KEY_ID); put("name", KEY_NAME); put("hint", "pully_sample")
        put("createdAt", SAMPLE_DATE); put("lastUsedAt", JsonNull)
    }

    private fun feedbackItem(id: String, text: String, status: String) = buildJsonObject {
        put("id", id); put("text", text); put("status", status); put("createdAt", SAMPLE_DATE)
    }

    private fun validateFeedback(args: JsonObject) {
        require(args.keys == setOf("text", "app", "screen", "device", "theme", "locale", "sentAt") + if ("place" in args) setOf("place") else emptySet())
        require(args.string("text") in FEEDBACK_TEXTS) { "Use one of the fixed sample feedback notes." }
        require(args.string("screen") in setOf("home", "review", "settings"))
        args["place"]?.let { require((it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content in setOf("pully/mobile-fixtures", "pully/mobile-fixtures/76")) }
        require(args.string("theme") in setOf("light", "dark") && args.string("locale") == "en")
        require(args.string("sentAt").length <= 40 && Instant.parse(args.string("sentAt")).toEpochMilliseconds() >= 0)
        val app = args["app"] as? JsonObject ?: error("The sample feedback app metadata is required.")
        app.exact("version", "versionCode")
        require(app.string("version").matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")))
        require((app["versionCode"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull in 1..10_000)
        val device = args["device"] as? JsonObject ?: error("The sample native feedback metadata is required.")
        device.exact("manufacturer", "model", "ios")
        require(device.string("manufacturer") == "Apple" && device.string("model") in setOf("iPhone", "iPad", "iPod touch"))
        require(device.string("ios").matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){0,2}")))
    }

    private fun stats(days: Int) = buildJsonObject {
        val last = Instant.parse(SAMPLE_DATE).toEpochMilliseconds()
        fun day(ago: Int) = Instant.fromEpochMilliseconds(last - ago * 86_400_000L).toString().take(10)
        put("from", day(days - 1)); put("to", day(0))
        put("totals", buildJsonObject {
            put("events", days * 100); put("sessions", days * 10 + 3); put("prsOpened", 5)
            put("filesReviewed", 12); put("aiRuns", 2); put("costUsd", 0.12); put("tokensIn", 1200); put("tokensOut", 300)
        })
        put("days", JsonArray((days - 1 downTo 0).map { ago -> buildJsonObject {
            put("day", day(ago)); put("sessions", if (ago == 0) days * 10 + 3 else 0)
            put("prsOpened", if (ago == 0) 5 else 0); put("filesReviewed", if (ago == 0) 12 else 0)
            put("aiRuns", if (ago == 0) 2 else 0); put("costUsd", if (ago == 0) 0.12 else 0.0)
        } }))
        put("tasks", JsonArray(listOf(buildJsonObject { put("task", "analyze"); put("runs", 2); put("tokensIn", 1200); put("tokensOut", 300); put("costUsd", 0.12); put("priced", 2) })))
        put("engines", JsonArray(listOf(buildJsonObject {
            put("engine", RootJourneyWork.ENGINE); put("model", RootJourneyWork.MODEL); put("runs", 2)
            put("tokensIn", 1200); put("tokensOut", 300); put("costUsd", 0.12); put("priced", 2)
        })))
        for (name in listOf("screens", "calls", "speed", "errors")) put(name, JsonArray(emptyList()))
        put("github", buildJsonObject { put("calls", 0); put("failures", 0) })
        put("cache", buildJsonObject { put("hit", 0); put("refetch", 0); put("stale", 0) })
    }

    private fun JsonObject.exact(vararg names: String) = require(keys == names.toSet()) { "The sample Settings arguments are invalid." }
    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("The sample Settings argument is invalid.")

    companion object {
        val OPERATIONS = setOf("agent-keys", "agent-key-create", "agent-key-revoke", "analytics-summary", "feedback-list", "feedback-send")
        const val KEY_NAME = "Fixture UIKit agent"
        const val KEY_ID = "fixture-agent-key"
        const val KEY_VALUE = "pully_sample_key_not_a_real_credential"
        const val FEEDBACK = "Fixture feedback after native text replacement."
        const val CODE_FEEDBACK = "fun review() = \"shared\""
        const val EARLIER_FEEDBACK = "Fixture feedback from before this launch."
        const val CREATE_ERROR = "Sample key creation unavailable. Try again."
        const val STATS_ERROR = "Sample stats unavailable. Try again."
        const val FEEDBACK_ERROR = "Sample feedback unavailable. Try again."
        private const val SAMPLE_DATE = "2026-10-21T10:00:00Z"
        private val FEEDBACK_TEXTS = setOf(FEEDBACK, CODE_FEEDBACK)
    }
}

/** Only a validated explicit sample operation can consume one deterministic failure. */
internal class RootJourneySettingsRejected(message: String) : Exception(message)
