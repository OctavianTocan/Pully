package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.ui.ai.critiqueVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** The Debug host supplies ONLY its existing UUID-isolated sample defaults, never ordinary app storage. */
internal interface RootJourneyStateStore {
    fun read(): String?
    fun write(state: String)
}

@Serializable
internal enum class RootJourneyScenario(val launchName: String) {
    Normal("normal"), StopRejectedOnce("stop-rejected-once"), AnalysisFailedOnce("analysis-failed-once"),
    SettingsRetryOnce("settings-retry-once");

    companion object {
        fun fromLaunch(value: String?): RootJourneyScenario =
            if (value.isNullOrEmpty()) Normal else entries.singleOrNull { it.launchName == value }
                ?: error("The sample journey scenario is invalid.")
    }
}

/** Only the explicit sample-server Stop scenario may produce this fixed, credential-free RPC rejection. */
internal class RootJourneyStopRejected : Exception()

/** Bounded virtual sample-server work. Its persisted deadlines survive a dead client without replaying a start. */
internal class RootJourneyWork(
    private val store: RootJourneyStateStore,
    private val nowMillis: () -> Long,
    private val scenario: RootJourneyScenario = RootJourneyScenario.Normal,
) {
    private val guard = Mutex()
    private val codec = Json { encodeDefaults = true }
    private var state = store.read()?.let(::restore) ?: SampleState(scenario = scenario)

    fun handles(name: String): Boolean = name in setOf(
        "review-data", "review-state", "reviewed-get", "reviewed-set", "review-comment", "review-submit", "review-discard",
        "ai-models", "lens-list", "ai-task-start", "ai-task-list", "ai-task-list-all", "ai-task-wait", "ai-task-cancel",
        "chat-history", "chat-clear", "chat-restore", "mermaid-svg",
    ) || name in RootJourneySettings.OPERATIONS

    fun validateRead(name: String, args: JsonObject) {
        val allowed = when (name) {
            "account-status", "account-me", "account-sign-out", "account-settings" -> emptySet()
            "account-sign-in" -> setOf("email", "password")
            "account-settings-merge" -> setOf("values")
            "github-pr" -> setOf("owner", "repo", "number", "lite", "fresh")
            "pr-overview" -> setOf("owner", "repo", "number", "cached")
            "pr-text", "review-data", "pr-stack" -> setOf("owner", "repo", "number")
            "review-state", "reviewed-get", "jev-files", "ai-task-list" -> setOf("key")
            "file-content" -> setOf("key", "path")
            "highlight" -> setOf("path", "lines", "dark")
            "my-pulls-cached" -> setOf("state", "revalidate", "classify")
            "my-pulls" -> setOf("state")
            "repo-pulls" -> setOf("owner", "repo", "next", "fresh")
            "github-stats" -> setOf("login")
            else -> return // Full operations have their exact typed checks in answer().
        }
        require(args.keys.all { it in allowed }) { "The sample operation arguments are invalid." }
        if (name == "highlight") {
            val lines = args["lines"] as? JsonArray ?: error("The sample code lines are required.")
            require(lines.size <= 400 && lines.all { it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("fun review() = \"android\"", "fun review() = \"shared\"") })
            require(args["dark"]?.jsonPrimitive?.booleanOrNull != null)
        }
    }

    suspend fun answer(name: String, args: JsonObject): JsonElement {
        if (name == "ai-task-wait") {
            val unchanged = guard.withLock {
                advance()
                val task = task(args)
                args.keysExactly("id", "after")
                val after = requireNotNull(args["after"]?.jsonPrimitive?.intOrNull)
                require(after in 0..task.rev) { "The sample task revision is invalid." }
                task.status == "running" && after == task.rev
            }
            if (unchanged) delay(250) // A genuine cancellable wait; unchanged snapshots cannot spin the real follower.
        }
        return guard.withLock {
            advance()
            if (name in RootJourneySettings.OPERATIONS) {
                try {
                    return@withLock state.settings.answer(name, args, scenario == RootJourneyScenario.SettingsRetryOnce)
                } finally {
                    // A consumed sample 503 is durable even though the client receives a failure.
                    persist()
                }
            }
            val result = when (name) {
                "review-data" -> { args.pullOnly(); reviewData() }
                "review-state" -> { args.keyOnly(); savedState() }
                "reviewed-get" -> { args.keyOnly(); obj { put("reviewed", JsonArray(if (state.reviewed) listOf(JsonPrimitive(BLOB)) else emptyList())); put("changedSinceReviewed", JsonArray(emptyList())) } }
                "reviewed-set" -> {
                    args.keysExactly("key", "shas", "reviewed"); args.key()
                    require(args["shas"] == JsonArray(listOf(JsonPrimitive(BLOB))))
                    state.reviewed = requireNotNull(args["reviewed"]?.jsonPrimitive?.booleanOrNull)
                    empty()
                }
                "review-comment" -> comment(args)
                "review-submit" -> submit(args)
                "review-discard" -> {
                    args.keysExactly("owner", "repo", "number", "pendingReviewId"); args.pull(); expectedPending(args)
                    state.comment = false; state.pendingComment = false; empty()
                }
                "ai-models" -> {
                    args.keysExactly("engine", "refresh"); require(args.text("engine") == ENGINE)
                    require(args["refresh"]?.jsonPrimitive?.booleanOrNull != null)
                    json("""{"models":[{"id":"$MODEL","name":"Fixture review model","description":"Nonnetwork sample model.","efforts":["minimal","medium","high"],"defaultEffort":"medium","isDefault":true},{"id":"$ALTERNATE_MODEL","name":"Fixture alternate model","description":"Second fixed sample model.","efforts":["minimal","medium","high"],"defaultEffort":"medium"}],"account":"Sample account","version":"sample-v1"}""")
                }
                "lens-list" -> { args.keysExactly(); JsonArray(emptyList()) }
                "ai-task-start" -> JsonPrimitive(start(args))
                "ai-task-list" -> { args.keyOnly(); JsonArray(state.tasks.map(::snapshot)) }
                "ai-task-list-all" -> { args.keysExactly(); JsonArray(state.tasks.map(::snapshot)) }
                "ai-task-wait" -> snapshot(task(args))
                "ai-task-cancel" -> {
                    args.keysExactly("id")
                    val task = task(args)
                    if (scenario == RootJourneyScenario.StopRejectedOnce && !state.failureConsumed && task.status == "running") {
                        state.failureConsumed = true
                        persist()
                        throw RootJourneyStopRejected()
                    }
                    task.takeIf { it.status == "running" }?.let { it.status = "cancelled"; it.rev++ }
                    empty()
                }
                "chat-history" -> {
                    args.keyOnly()
                    obj { put("chats", JsonArray(state.history.map { chat -> obj {
                        put("id", "fixture-history-$chat"); put("title", question(chat)); put("at", SAMPLE_DATE); put("turns", 1)
                    } })) }
                }
                "chat-clear" -> {
                    args.keyOnly(); require(state.tasks.none { it.kind == "chat" && it.status == "running" })
                    archiveChat(); state.chat = "empty"; empty()
                }
                "chat-restore" -> {
                    args.keysExactly("key", "id"); args.key()
                    val restored = args.text("id").removePrefix("fixture-history-")
                    require(args.text("id") == "fixture-history-$restored" && restored in state.history)
                    archiveChat(); state.history.remove(restored); state.chat = restored; empty()
                }
                "mermaid-svg" -> {
                    args.keysExactly("source", "dark")
                    require(args.text("source") == MERMAID && args["dark"]?.jsonPrimitive?.booleanOrNull != null)
                    obj { put("svg", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 64"><rect x="1" y="1" width="318" height="62" rx="8" fill="#e8f4ef" stroke="#22634a"/><text x="16" y="38" font-size="16" fill="#18342b">Shared state to native adapters</text></svg>""") }
                }
                else -> error("Unsupported complete sample operation.")
            }
            persist()
            result
        }
    }

    private fun start(args: JsonObject): String {
        args.key()
        val kind = args.text("kind")
        require(kind in setOf("analyze", "critique", "chat")) { "This sample supports its bounded Analyze and Chat journey only." }
        val model = args["model"] as? JsonObject ?: error("The sample model is required.")
        require(model.keys.all { it in setOf("engine", "model", "effort") })
        require(model.text("engine") == ENGINE && model.textOrNull("model") in setOf(null, MODEL, ALTERNATE_MODEL))
        val effort = model.textOrNull("effort")
        require(effort == null || effort in setOf("minimal", "medium", "high")) { "The sample model effort is invalid." }
        require(state.clientStarts < 8 && state.tasks.size < MAX_TASKS)
        require(state.tasks.none { it.kind == kind && it.status == "running" }) { "The sample task is already running." }
        val then: List<String>
        val variant: String?
        val chat: String?
        when (kind) {
            "analyze" -> {
                args.keysExactly("kind", "key", "model", "locale", "then")
                require(args.text("locale") == "en")
                require(args["then"] == JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief"))))
                require(state.tasks.count { it.kind == "analyze" } < 2)
                then = listOf("tour", "brief"); variant = null; chat = null
            }
            "critique" -> {
                args.keysExactly("kind", "key", "model", "variant")
                require(effort == "high")
                variant = critiqueVariant(HEAD, ENGINE, model.textOrNull("model").orEmpty(), effort, null)
                require(args.text("variant") == variant)
                then = emptyList(); chat = null
            }
            else -> {
                require(args.keys.all { it in setOf("kind", "key", "model", "locale", "text", "retry") })
                require(args.text("locale") == "en")
                require(args["retry"] == null || args["retry"]?.jsonPrimitive?.booleanOrNull == true)
                val text = args.textOrNull("text")
                chat = if (text == QUESTION || (text == null && state.chat == "current")) "current"
                    else if (text == SEED_QUESTION || (text == null && state.chat == "seed")) "seed"
                    else error("Use the fixed sample question in this fixture.")
                require(text != null || args["retry"]?.jsonPrimitive?.booleanOrNull == true)
                then = emptyList(); variant = null
            }
        }
        val duration = duration(kind)
        val acceptedAt = nowMillis()
        require(acceptedAt >= 0 && acceptedAt <= Long.MAX_VALUE - duration)
        state.clientStarts++
        val task = createTask(kind, acceptedAt, duration, then, variant, chat)
        persist() // The task identity/deadline is durable before its acknowledgement is returned.
        return task.id
    }

    private fun createTask(kind: String, at: Long, duration: Long, then: List<String> = emptyList(), variant: String? = null, chat: String? = null): SampleTask {
        require(state.tasks.size < MAX_TASKS && at >= 0 && at <= Long.MAX_VALUE - duration)
        return SampleTask("fixture-task-${++state.nextId}", kind, at, at + duration, then = then, variant = variant, chat = chat)
            .also { state.tasks += it }
    }

    private fun advance() {
        val now = nowMillis()
        require(now >= 0)
        var changed: Boolean
        do {
            changed = false
            state.tasks.toList().filter { it.status == "running" && it.dueAt <= now }.forEach { task ->
                changed = true
                task.status = "done"; task.rev++
                if (scenario == RootJourneyScenario.AnalysisFailedOnce && !state.failureConsumed && task.kind == "analyze") {
                    state.failureConsumed = true
                    task.status = "failed"
                    return@forEach
                }
                when (task.kind) {
                    "analyze" -> {
                        state.analysis = task.id
                        task.then.forEach { pass -> createTask(pass, task.dueAt, PASS_MILLIS) }
                    }
                    "tour" -> state.tour = task.id
                    "brief" -> state.brief = task.id
                    "critique" -> { state.critique = task.id; state.variant = task.variant }
                    "chat" -> state.chat = requireNotNull(task.chat)
                }
            }
        } while (changed)
        persist()
    }

    private fun comment(args: JsonObject): JsonElement {
        require(args.keys.all { it in setOf("owner", "repo", "number", "path", "side", "line", "headSha", "body", "mode", "startLine") })
        args.pull()
        require(args.text("path") == FILE && args.text("side") == "additions" && args["line"]?.jsonPrimitive?.intOrNull == 1)
        require(args["startLine"] == null || args["startLine"]?.jsonPrimitive?.intOrNull == 1)
        require(args.text("headSha") == HEAD && args.text("body") == COMMENT && args.text("mode") in setOf("review", "single"))
        require(!state.comment) { "The sample comment already exists." }
        state.comment = true; state.pendingComment = args.text("mode") == "review"
        return empty()
    }

    private fun submit(args: JsonObject): JsonElement {
        args.keysExactly("owner", "repo", "number", "verdict", "body", "headSha", "pendingReviewId")
        args.pull(); expectedPending(args)
        require(args.text("verdict") == "COMMENT" && args.text("headSha") == HEAD)
        require(args.text("body") == SUMMARY || (args.text("body").isEmpty() && state.pendingComment))
        require(!state.submitted)
        state.submitted = true; state.pendingComment = false
        return empty()
    }

    private fun expectedPending(args: JsonObject) = require(args["pendingReviewId"] == if (state.pendingComment) JsonPrimitive(PENDING_REVIEW) else JsonNull) {
        "The sample pending review changed."
    }

    private fun reviewData(): JsonElement = obj {
        put("canWrite", true); put("viewer", "octavian")
        put("pendingReview", if (state.pendingComment) obj { put("id", PENDING_REVIEW) } else JsonNull)
        put("threads", JsonArray(if (!state.comment) emptyList() else listOf(obj {
            put("rootId", 7602); put("threadId", "fixture-thread-76"); put("path", FILE); put("side", "additions"); put("line", 1)
            put("pending", state.pendingComment); put("resolved", false)
            put("comments", JsonArray(listOf(obj {
                put("id", 7602); put("author", obj { put("login", "octavian") }); put("body", COMMENT)
                put("createdAt", SAMPLE_DATE); put("pending", state.pendingComment)
            })))
        })))
        put("summaries", JsonArray(if (!state.submitted) emptyList() else listOf(obj {
            put("id", 7603); put("author", obj { put("login", "octavian") }); put("body", SUMMARY); put("state", "commented"); put("submittedAt", SAMPLE_DATE)
        })))
    }

    private fun savedState(): JsonElement = obj {
        if (state.analysis != null) {
            put("analysis", json("""{"overallSummary":"Fixture analysis keeps the actual shared review state.","files":[{"path":"$FILE","summary":"The native adapters consume the same review.","hunks":[]}],"generatedAt":"$SAMPLE_DATE"}"""))
            put("aiGroups", json("""[{"id":"fixture-shared","label":"Shared review","paths":["$FILE"],"children":[]}]"""))
        }
        state.tour?.let { id -> put("tour", json("""{"title":"Fixture tour from the shared review","overview":"This sample uses the actual shared Tour reader.","headSha":"$HEAD","generatedAt":"${generatedAt(id)}","stops":[{"title":"One shared session","purpose":"Read the same state on both phones.","files":["$FILE"],"takeaway":"Native adapters keep the shared review.","blocks":[{"kind":"text","text":"The shared session owns task observations and saved results."}],"sources":[{"path":"$FILE","line":1,"why":"Open the shared review line"}]}]}""")) }
        state.brief?.let { id -> put("brief", obj {
            put("headSha", HEAD); put("generatedAt", generatedAt(id))
            put("tree", obj { put("type", "Stack"); put("props", obj { put("children", JsonArray(listOf(
                block("Text", obj { put("markdown", "Fixture typed Brief uses the same production reader.") }),
                block("Code", obj { put("code", "fun review() = \"shared\""); put("language", "kotlin"); put("caption", "Fixture shared code") }),
                block("Callout", obj { put("tone", "good"); put("text", "One shared session keeps the native journey.") }),
                block("Diagram", obj { put("mermaid", MERMAID); put("caption", "Fixture shared state") }),
            ))) }) })
        }) }
        state.critique?.let { put("critique", obj {
            put("headSha", HEAD); put("variant", state.variant); put("revision", "fixture-critique-revision"); put("generatedAt", SAMPLE_DATE)
            put("findings", json("""[{"id":"fixture-finding-1","severity":"risk","title":"Read the source ownership guard","body":"This is a sample finding.","path":"$FILE","line":1,"side":"additions"}]"""))
        }) }
        put("chat", if (state.chat == "empty") JsonArray(emptyList()) else JsonArray(listOf(
            obj { put("role", "user"); put("text", question(state.chat)) },
            obj { put("role", "assistant"); put("text", answer(state.chat)) },
        )))
    }

    private fun generatedAt(id: String): String = Instant.fromEpochMilliseconds(state.tasks.single { it.id == id }.dueAt).toString()
    private fun snapshot(task: SampleTask): JsonObject = obj {
        put("id", task.id); put("key", RootJourneyTransport.KEY); put("kind", task.kind); put("label", when (task.kind) {
            "analyze" -> "Analyzing"; "tour" -> "Writing the tour"; "brief" -> "Writing the brief"; "critique" -> "Reviewing"; else -> "Answering"
        })
        put("rev", task.rev); put("status", task.status); put("startedAt", task.startedAt)
        if (task.status != "running") put("endedAt", task.dueAt)
        if (task.status == "failed") put("error", ANALYSIS_ERROR)
        if (task.then.isNotEmpty()) put("then", JsonArray(task.then.map(::JsonPrimitive)))
        task.chat?.let { put("text", question(it)) }
    }

    private fun task(args: JsonObject) = state.tasks.singleOrNull { it.id == args.text("id") } ?: error("The sample task does not exist.")
    private fun archiveChat() { if (state.chat != "empty" && state.chat !in state.history) state.history.add(0, state.chat) }
    private fun question(chat: String) = if (chat == "seed") SEED_QUESTION else QUESTION
    private fun answer(chat: String) = if (chat == "seed") SEED_ANSWER else ANSWER
    private fun persist() { store.write(codec.encodeToString(state)) }
    private fun duration(kind: String): Long = when (kind) {
        "chat" -> 750L
        "tour", "brief" -> PASS_MILLIS
        else -> if (scenario == RootJourneyScenario.StopRejectedOnce) STOP_WORK_MILLIS else WORK_MILLIS
    }

    private fun restore(raw: String): SampleState {
        require(raw.length <= 32_000) { "The sample journey state is invalid." }
        val restored = try {
            require(Json.parseToJsonElement(raw).jsonObject["version"] == JsonPrimitive(1))
            codec.decodeFromString<SampleState>(raw)
        } catch (_: Exception) { error("The sample journey state is invalid.") }
        require(restored.version == 1 && restored.nextId in 0..MAX_TASKS && restored.clientStarts in 0..8)
        require(restored.scenario == scenario && (!restored.failureConsumed || scenario in setOf(RootJourneyScenario.StopRejectedOnce, RootJourneyScenario.AnalysisFailedOnce)))
        restored.settings.validate(scenario == RootJourneyScenario.SettingsRetryOnce)
        require(restored.tasks.size == restored.nextId && restored.tasks.map { it.id }.toSet() == (1..restored.nextId).map { "fixture-task-$it" }.toSet())
        require(restored.tasks.count { it.kind in setOf("analyze", "critique", "chat") } == restored.clientStarts)
        require(restored.history.size <= 2 && restored.history.distinct().size == restored.history.size && restored.history.all { it in setOf("seed", "current") })
        require(restored.chat in setOf("empty", "seed", "current") && (!restored.pendingComment || restored.comment && !restored.submitted))
        val variants = listOf("", MODEL, ALTERNATE_MODEL).map { critiqueVariant(HEAD, ENGINE, it, "high", null) }.toSet()
        require(restored.variant == null || restored.variant in variants)
        restored.tasks.forEach { task ->
            require(task.id.matches(Regex("fixture-task-[1-9][0-9]?")) && task.id.removePrefix("fixture-task-").toInt() <= restored.nextId)
            require(task.kind in setOf("analyze", "tour", "brief", "critique", "chat") && task.status in setOf("running", "done", "cancelled", "failed"))
            require(task.startedAt >= 0 && task.dueAt >= task.startedAt && task.dueAt - task.startedAt == duration(task.kind))
            if (task.status == "failed") require(scenario == RootJourneyScenario.AnalysisFailedOnce && restored.failureConsumed && task.kind == "analyze")
            require(task.rev == if (task.status == "running") 1 else 2)
            require(task.then == if (task.kind == "analyze") listOf("tour", "brief") else emptyList<String>())
            require(if (task.kind == "chat") task.chat in setOf("seed", "current") else task.chat == null)
            require(if (task.kind == "critique") task.variant in variants else task.variant == null)
            if (task.kind in setOf("tour", "brief")) require(restored.tasks.any { it.kind == "analyze" && it.status == "done" && it.dueAt == task.startedAt })
        }
        require(restored.tasks.count { it.status == "failed" } == if (scenario == RootJourneyScenario.AnalysisFailedOnce && restored.failureConsumed) 1 else 0)
        mapOf("analyze" to restored.analysis, "tour" to restored.tour, "brief" to restored.brief, "critique" to restored.critique).forEach { (kind, id) ->
            require(id == null || restored.tasks.any { it.id == id && it.kind == kind && it.status == "done" })
        }
        return restored
    }

    private fun JsonObject.key() = require(text("key") == RootJourneyTransport.KEY) { "The sample cannot access another review." }
    private fun JsonObject.keyOnly() { keysExactly("key"); key() }
    private fun JsonObject.pull() = require(text("owner") == "pully" && text("repo") == "mobile-fixtures" && this["number"]?.jsonPrimitive?.intOrNull == 76)
    private fun JsonObject.pullOnly() { keysExactly("owner", "repo", "number"); pull() }
    private fun JsonObject.keysExactly(vararg names: String) = require(keys == names.toSet()) { "The sample operation arguments are invalid." }
    private fun JsonObject.text(name: String): String = requireNotNull(textOrNull(name)) { "The sample operation arguments are invalid." }
    private fun JsonObject.textOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun block(type: String, props: JsonObject) = obj { put("type", type); put("props", props) }
    private fun obj(build: JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
    private fun empty() = JsonObject(emptyMap())
    private fun json(raw: String) = Json.parseToJsonElement(raw)

    companion object {
        const val HEAD = "fixture-76"
        const val FILE = "src/Review.kt"
        const val BLOB = "fixture-review-blob"
        const val ENGINE = "claude-code"
        const val MODEL = "fixture-review-model"
        const val ALTERNATE_MODEL = "fixture-alternate-model"
        const val COMMENT = "Fixture line comment."
        const val SUMMARY = "Fixture review summary."
        const val QUESTION = "What changed in this shared review?"
        const val ANSWER = "The fixture keeps one shared review session and its native adapters."
        const val SEED_QUESTION = "A previous fixture question"
        const val SEED_ANSWER = "The earlier fixture conversation is restored through the real client."
        const val MERMAID = "flowchart LR\n  Shared[Shared state] --> Native[Native adapters]"
        const val STOP_ERROR = "Sample stop unavailable."
        const val ANALYSIS_ERROR = "Sample analysis failed. Try again."
        private const val SAMPLE_DATE = "2026-10-21T10:00:00Z"
        private const val PENDING_REVIEW = 7601L
        // Long enough that a slow simulator UI run still sees the task running after opening the Tour.
        private const val WORK_MILLIS = 25_000L
        private const val STOP_WORK_MILLIS = 45_000L
        private const val PASS_MILLIS = 2_000L
        /** When a started analysis and its follow-up passes are certainly done. */
        const val COMPLETION_MILLIS = WORK_MILLIS + PASS_MILLIS + 1_000L
        private const val MAX_TASKS = 16
    }
}

@Serializable
private data class SampleState(
    val version: Int = 1,
    val scenario: RootJourneyScenario = RootJourneyScenario.Normal,
    var failureConsumed: Boolean = false,
    var nextId: Int = 0,
    var clientStarts: Int = 0,
    var reviewed: Boolean = false,
    var comment: Boolean = false,
    var pendingComment: Boolean = false,
    var submitted: Boolean = false,
    var analysis: String? = null,
    var tour: String? = null,
    var brief: String? = null,
    var critique: String? = null,
    var variant: String? = null,
    var chat: String = "empty",
    val history: MutableList<String> = mutableListOf("seed"),
    val tasks: MutableList<SampleTask> = mutableListOf(),
    val settings: RootJourneySettings = RootJourneySettings(),
)

@Serializable
private data class SampleTask(
    val id: String,
    val kind: String,
    val startedAt: Long,
    val dueAt: Long,
    var rev: Int = 1,
    var status: String = "running",
    val then: List<String> = emptyList(),
    val variant: String? = null,
    val chat: String? = null,
)
