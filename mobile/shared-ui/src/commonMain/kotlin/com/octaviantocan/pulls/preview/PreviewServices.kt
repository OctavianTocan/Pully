package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.home.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The fixture host uses the actual shared RPC builders/parsers, with a fixed in-memory transport and no network. */
fun previewServices(
    native: NativeHomeActions,
    settings: AppSettings,
    storage: KeyValueStore,
    pageStorage: RepoPageStorage,
    unavailable: (HomePreviewFeature) -> Unit,
): HomeScreenServices {
    val repo = Repo({ RepoConfiguration(PREVIEW_SERVER) }, previewTransport)
    val current = { true } // One fixed, public fixture source for this controller's lifetime.
    val sizes = PullSizes.source(requireNotNull(sourceStorageNamespace(PREVIEW_SERVER, null, false)))
    val effects = object : HomeScreenEffects {
        // Internal fixture interaction is not sent to a production analytics or synchronization service.
        override fun track(name: String, vararg props: Pair<String, Any?>) = Unit
        override fun error(name: String, message: String?) = Unit
        override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) = sizes.put(owner, repo, number, additions, deletions, files)
    }
    val home = HomeState(
        MyPullsSync(
            cached = { repo.myPullsCached(it)?.asSnapshot() }, fresh = { repo.myPulls(it) }, storage = storage,
            peek = { state, classify -> repo.myPullsCached(state, revalidate = false, classify = classify)?.asSnapshot() }, currentSource = current,
        ),
        ArchiveStore(storage), TourIndex(storage), InboxLayout(storage),
    )
    val data = HomeScreenData(
        repo, FirstPages(pageStorage, current), PostedDiffsStore(repo, storage, { false }, current),
        GithubStatsStore(repo, storage, current), current, effects, sizes,
    )
    return HomeScreenServices(
        repo, settings, home, data, native, effects,
        capabilities = HomeScreenCapabilities(
            reviewRoutes = false, tourRoutes = false, startTour = false, inboxAsk = false,
            taskObservation = false, postedDiffs = false, deletePosted = false, profileStats = true,
            settingsRoute = false, commands = false,
        ),
        unavailable = unavailable, openCommands = { unavailable(HomePreviewFeature.Commands) },
    )
}

const val PREVIEW_SERVER = "https://fixtures.pully.invalid"

private fun previewPull(number: Int, title: String, author: String, state: String, additions: Int, deletions: Int, updated: Instant = Instant.parse("2026-10-21T10:00:00Z")) = PullRow(
    owner = "pully", repo = "mobile-fixtures", number = number, title = title, author = author,
    updatedAt = updated.toString(), updatedAtMs = updated.toEpochMilliseconds(),
    isDraft = state == "draft", state = if (state == "closed" || state == "merged") "closed" else "open",
    role = if (author == "octavian") "authored" else "review-requested",
    merged = state == "merged", checks = "success", additions = additions, deletions = deletions,
    headSha = "fixture-$number", url = "https://github.com/pully/mobile-fixtures/pull/$number",
)

private val fixtures = listOf(
    previewPull(76, "Share the existing Pully review interface", "octavian", "open", 180, 32),
    previewPull(75, "Keep the same type, colors and state tiles on both phones", "octavian", "draft", 24, 8),
    previewPull(74, "Preserve the file review workflow", "reviewer", "open", 1200, 640),
    previewPull(73, "An intentionally long pull request title that keeps the same two line limit as the Android inbox", "reviewer", "closed", 17, 12),
    previewPull(72, "Keep completed pull requests in the closed list", "octavian", "merged", 320, 20),
) + List(24) { index ->
    previewPull(
        70 - index,
        if (index == 23) "Fixture row kept after repository navigation" else "Shared inbox fixture ${index + 1}",
        "reviewer", if (index % 2 == 1) "draft" else "open", 10 + index, index,
        Instant.parse("2026-10-20T00:00:00Z") - index.hours,
    )
}

private val previewTransport = RpcTransport { request ->
    require(request.url == "$PREVIEW_SERVER/api/rpc" && request.headers.isEmpty()) { "Fixture requests never leave their fixed source." }
    val call = Json.parseToJsonElement(request.body).jsonObject
    val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
    val state = args["state"]?.jsonPrimitive?.content ?: "open"
    val rows = fixtures.filter { it.state == state }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val result: JsonElement = when (val name = call["name"]?.jsonPrimitive?.content) {
        "my-pulls-cached" -> buildJsonObject { put("pulls", JsonArray(rows.map { it.toJson() })); put("fetchedAt", now) }
        "my-pulls" -> JsonArray(rows.map { it.toJson() })
        "repo-pulls" -> buildJsonObject {
            val owner = args["owner"]?.jsonPrimitive?.content
            val repo = args["repo"]?.jsonPrimitive?.content
            val mine = fixtures.filter { it.state == "open" && it.owner == owner && it.repo == repo }
            put("items", JsonArray(mine.map { pull -> buildJsonObject {
                put("number", pull.number); put("title", pull.title); put("url", pull.url)
                put("state", if (pull.isDraft) "draft" else "open")
                put("author", buildJsonObject { put("login", pull.author) })
                put("checks", pull.checks); put("additions", pull.additions); put("deletions", pull.deletions)
                put("updatedAt", pull.updatedAt); put("createdAt", pull.updatedAt)
            } }))
            put("totalCount", mine.size); put("cachedAt", now)
        }
        "github-stats" -> buildJsonObject {
            val days = List(361) { (it * 7) % 13 }
            put("login", "octavian"); put("total", days.sum()); put("commits", 480); put("pullRequests", 76)
            put("reviews", 133); put("streak", 4); put("longestStreak", 28); put("firstDay", "2025-10-26")
            put("days", JsonArray(days.map(::JsonPrimitive)))
        }
        else -> error("The internal preview does not support $name.")
    }
    RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")
}
