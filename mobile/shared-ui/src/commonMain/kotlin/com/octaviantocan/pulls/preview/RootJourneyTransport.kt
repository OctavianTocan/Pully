package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.serialization.json.*

/** Nonnetwork fixture for the REAL root. The default remains strict reading; full work needs the explicit Debug entry. */
internal class RootJourneyTransport private constructor(private val work: RootJourneyWork?, val fixtureIdentifier: String?) : RpcTransport {
    constructor() : this(null, null)
    val fullJourney: Boolean get() = work != null
    override suspend fun execute(request: RpcRequest): RpcResponse {
        require(request.url == "$PREVIEW_SERVER/api/rpc") { "The root fixture cannot leave its fixed sample server." }
        require(request.headers.isEmpty() || request.headers == mapOf("X-Pully-Session" to SESSION)) { "The root fixture cannot accept real credentials." }
        require(request.body.length <= 64_000) { "The sample RPC envelope is too large." }
        val call = Json.parseToJsonElement(request.body).jsonObject
        require(call.keys == setOf("name", "args")) { "The sample RPC envelope is invalid." }
        val name = call.getValue("name").jsonPrimitive.content
        val args = requireNotNull(call["args"] as? JsonObject) { "The sample RPC arguments must be an object." }
        work?.validateRead(name, args)
        if (name !in setOf("account-status", "account-sign-in")) require(request.headers["X-Pully-Session"] == SESSION) { "Sign in to the sample account first." }
        if (name in setOf("github-pr", "pr-overview", "pr-text", "review-data", "pr-stack")) {
            require(args["owner"]?.jsonPrimitive?.content == "pully" && args["repo"]?.jsonPrimitive?.content == "mobile-fixtures" && args["number"]?.jsonPrimitive?.intOrNull == 76) {
                "This journey fixture contains pull request #76 only."
            }
        }
        if (name in setOf("review-state", "reviewed-get", "jev-files", "file-content", "ai-task-list")) {
            require(args["key"]?.jsonPrimitive?.content == KEY) { "The fixture cannot read another review." }
        }
        if (name in setOf("file-content", "highlight")) {
            val path = args["path"]?.jsonPrimitive?.content
            require(path == "src/Review.kt" || name == "highlight" && work != null && path == "snippet.kt") { "The fixture cannot read another file." }
        }
        if (name in setOf("my-pulls-cached", "my-pulls", "repo-pulls", "github-stats")) {
            return previewTransport.execute(RpcRequest(request.url, emptyMap(), request.body))
        }
        val result: JsonElement = try { if (work?.handles(name) == true) work.answer(name, args) else when (name) {
            "account-status" -> json("""{"hasAccount":true}""")
            "account-sign-in" -> {
                require(request.headers.isEmpty())
                require(args["email"]?.jsonPrimitive?.content == EMAIL && args["password"]?.jsonPrimitive?.content == PASSWORD) { "Use the sample account shown by this test fixture." }
                buildJsonObject { put("session", SESSION); put("email", EMAIL) }
            }
            "account-me" -> buildJsonObject { put("email", EMAIL) }
            "account-sign-out" -> JsonObject(emptyMap())
            "account-settings" -> json("""{"values":{}}""")
            "account-settings-merge" -> buildJsonObject { put("values", args["values"] ?: JsonObject(emptyMap())) }
            "github-pr" -> opened
            "pr-overview" -> overview
            "pr-text" -> overview.jsonObject.getValue("overview")
            "review-state", "reviewed-get", "review-data" -> JsonObject(emptyMap())
            "ai-task-list" -> JsonArray(emptyList())
            // Settings' AI rows read the sample account's sign-ins; the fixture never starts one.
            "ai-login-status" -> json("""{"account":true,"claude":{"signedIn":true,"email":"$EMAIL","plan":"max"},"codex":{"signedIn":false}}""")
            "jev-files" -> json("""{"enabled":false}""")
            "pr-stack" -> json("""{"stack":null}""")
            "file-content" -> json("""{"old":"fun review() = \"android\"\n","new":"fun review() = \"shared\"\n"}""")
            "highlight" -> JsonObject(emptyMap()) // The exact renderer's normal unhighlighted-text path.
            else -> error("The root fixture rejects unsupported RPC: $name")
        } } catch (_: RootJourneyStopRejected) {
            return RpcResponse(503, buildJsonObject { put("error", RootJourneyWork.STOP_ERROR) }.toString(), "application/json")
        } catch (failure: RootJourneySettingsRejected) {
            return RpcResponse(503, buildJsonObject { put("error", requireNotNull(failure.message)) }.toString(), "application/json")
        }
        return RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")
    }

    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private val opened = json("""{
      "key":"root-fixture-76","groups":[],"diff":{
        "title":"Share the existing Pully review interface","label":"#76","url":"https://github.com/pully/mobile-fixtures/pull/76",
        "head":{"sha":"fixture-76","ref":"shared-review"},"base":{"sha":"fixture-base","ref":"main"},
        "pullRequest":{"state":"open"},"ref":{"kind":"github-pr"},
        "files":[{"path":"src/Review.kt","sha":"fixture-review-blob","status":"modified","additions":1,"deletions":1,
          "hunks":[{"header":"@@ -1 +1 @@","oldStart":1,"oldLines":1,"newStart":1,"newLines":1,
            "patch":"-fun review() = \"android\"\n+fun review() = \"shared\""}]}]
      }
    }""")

    private val overview = json("""{"overview":{
      "id":"fixture-pr-76","number":76,"title":"Share the existing Pully review interface",
      "url":"https://github.com/pully/mobile-fixtures/pull/76","state":"open",
      "body":"Actual shared review fixture description. This is sample data.",
      "author":{"login":"octavian"},"createdAt":"2026-10-21T10:00:00Z",
      "head":{"oid":"fixture-76","ref":"shared-review","owner":"pully"},"base":{"oid":"fixture-base","ref":"main"},
      "viewer":{"canEditText":false},"checksSummary":{"passing":1,"total":1,"state":"success"},
      "checks":[{"id":"fixture-check","name":"Shared tests","bucket":"passing","conclusion":"success"}],
      "commits":[{"oid":"fixture-76","messageHeadline":"Share the review interface","author":{"name":"octavian"}}],
      "timeline":[],"merge":{"methods":[],"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN"}
    }}""")

    companion object {
        const val EMAIL = "root-fixture@pully.invalid"
        const val PASSWORD = "sample-root-password"
        const val SESSION = "sample-root-session-not-a-real-credential"
        const val KEY = "root-fixture-76"
        fun readOnly(fixtureIdentifier: String) = RootJourneyTransport(null, canonicalIdentifier(fixtureIdentifier))
        fun fullJourney(
            store: RootJourneyStateStore,
            fixtureIdentifier: String? = null,
            scenario: RootJourneyScenario = RootJourneyScenario.Normal,
            nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        ): RootJourneyTransport {
            val identity = fixtureIdentifier?.let(::canonicalIdentifier)
            return RootJourneyTransport(RootJourneyWork(store, nowMillis, scenario), identity)
        }
        private fun canonicalIdentifier(value: String): String {
            require(value.matches(Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"))) {
                "The root journey fixture requires its isolated UUID."
            }
            return value.lowercase()
        }
    }
}
