package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.asPulls
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------
// The home URL field (`routeFromGithubUrl` in `source-routes.ts`, plus the phone's share
// and paste forms from `PrLinks`).
// ---------------------------------------------------------------------------------------

/** What the home page's URL field opens. */
sealed interface HomeLink {
    data class Target(val target: OpenTarget) : HomeLink
    data class Repo(val owner: String, val repo: String) : HomeLink
}

object HomeLinks {
    private val github = Regex("""github\.com/([^/\s]+)/([^/\s#?]+)(?:/(pull|commit|compare)/([^\s#?]+)|/pulls/?)?(?:[/?#]|$)""")

    /** A GitHub pull request, commit, compare or repository URL; otherwise whatever [PrLinks] understands (`owner/repo#1`, a pulls.review link). */
    fun parse(text: String): HomeLink? {
        val trimmed = text.trim()
        github.find(trimmed)?.let { match ->
            val (owner, repo, kind, rest) = match.destructured
            return when (kind) {
                "pull" -> Regex("""^\d+""").find(rest)?.value?.let { HomeLink.Target(OpenTarget.Pull(owner, repo, it.toInt(), "github.com")) }
                "commit" -> HomeLink.Target(OpenTarget.Commit(owner, repo, rest.replace(Regex("/.*$"), "")))
                "compare" -> splitRange(rest)?.let { (base, head) -> HomeLink.Target(OpenTarget.Compare(owner, repo, base, head)) }
                else -> HomeLink.Repo(owner, repo)
            }
        }
        return PrLinks.parse(trimmed)?.let { HomeLink.Target(it) }
    }

    /** `base...head`, both sides non-empty. */
    fun splitRange(range: String): Pair<String, String>? {
        val dots = range.indexOf("...")
        return if (dots > 0 && dots + 3 < range.length) range.substring(0, dots) to range.substring(dots + 3) else null
    }
}

// ---------------------------------------------------------------------------------------
// `formatTimeAgo` (`i18n/time-ago.ts`): the unit choice. The words come from ICU in [timeAgo].
// ---------------------------------------------------------------------------------------

enum class TimeUnit(val ms: Long) {
    Year(365L * 24 * 60 * 60 * 1000),
    Month(30L * 24 * 60 * 60 * 1000),
    Week(7L * 24 * 60 * 60 * 1000),
    Day(24L * 60 * 60 * 1000),
    Hour(60L * 60 * 1000),
    Minute(60L * 1000),
    Second(1000),
}

/** The largest whole unit (signed, negative in the past), `0 seconds` under a minute — `Math.round` like the web. */
fun relativeTime(thenMs: Long, nowMs: Long): Pair<Long, TimeUnit> {
    val elapsed = (thenMs - nowMs).toDouble()
    for (unit in TimeUnit.entries) {
        if (unit == TimeUnit.Second) break
        if (abs(elapsed) >= unit.ms) return Math.round(elapsed / unit.ms) to unit
    }
    return 0L to TimeUnit.Second
}

// ---------------------------------------------------------------------------------------
// `useMyPulls` (`components/home/use-my-pulls.ts`): device copy, then the server cache,
// then a fresh list, with a generation guard against answers for an old state.
// ---------------------------------------------------------------------------------------

/** `my-pulls-cached`'s answer. */
data class PullsSnapshot(val pulls: List<PullRow>, val fetchedAt: Long, val refreshing: Boolean = false)

fun JsonObject.asSnapshot(): PullsSnapshot? {
    val at = (this["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: return null
    return PullsSnapshot(asPulls(), at, (this["refreshing"] as? JsonPrimitive)?.contentOrNull == "true")
}

/** A string store (SharedPreferences on the phone, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

class MyPullsSync(
    private val cached: suspend (state: String) -> PullsSnapshot?,
    private val fresh: suspend (state: String) -> List<PullRow>,
    private val storage: KeyValueStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** A server copy younger than this is shown without asking GitHub again. */
    private val freshForMs: Long = 30_000,
) {
    /** The newest list known, null until any copy arrives. */
    var pulls by mutableStateOf<List<PullRow>?>(null)
        private set

    /** When that list was fetched from GitHub. */
    var fetchedAt by mutableStateOf<Long?>(null)
        private set

    var updating by mutableStateOf(false)
        private set

    /** Why the last update failed; the previous list stays in [pulls]. */
    var error by mutableStateOf<String?>(null)
        private set

    private var state = "open"
    private var generation = 0

    /** Shows the device copy for [state] at once, then loads. */
    suspend fun select(state: String) {
        this.state = state
        val stored = readStored(state)
        pulls = stored?.pulls
        fetchedAt = stored?.fetchedAt
        load(false)
    }

    /** A fresh list even when the current one is recent. */
    suspend fun reload() = load(true)

    private suspend fun load(force: Boolean) {
        val current = ++generation
        val selected = state
        fun isCurrent() = current == generation
        fun show(value: PullsSnapshot) {
            if (!isCurrent() || (fetchedAt != null && value.fetchedAt < fetchedAt!!)) return
            pulls = value.pulls
            fetchedAt = value.fetchedAt
            writeStored(selected, value)
        }
        updating = true
        error = null
        try {
            val server = try { cached(selected) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (server != null) show(server)
            if (!force && server != null && !server.refreshing && now() - server.fetchedAt < freshForMs) return
            val list = fresh(selected)
            show(PullsSnapshot(list, now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent()) error = e.message ?: e.toString()
        } finally {
            if (isCurrent()) updating = false
        }
    }

    private fun readStored(state: String): PullsSnapshot? = runCatching {
        val raw = storage?.get(STORAGE_PREFIX + state) ?: return null
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (obj["pulls"] !is JsonArray) return null
        obj.asSnapshot()
    }.getOrNull()

    private fun writeStored(state: String, value: PullsSnapshot) {
        runCatching {
            storage?.set(STORAGE_PREFIX + state, buildJsonObject {
                put("pulls", JsonArray(value.pulls.map { it.toJson() }))
                put("fetchedAt", value.fetchedAt)
            }.toString())
        }
    }

    companion object {
        const val STORAGE_PREFIX = "pulls-review:my-pulls:v1:"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The `my-pulls` shape again, so the device copy reads back through `asPulls`. */
fun PullRow.toJson(): JsonObject = buildJsonObject {
    put("owner", owner); put("repo", repo); put("number", number); put("title", title); put("author", author)
    put("updatedAt", updatedAt); put("isDraft", isDraft); put("role", role)
    if (checks.isNotEmpty()) put("checks", checks)
    if (reviewDecision.isNotEmpty()) put("reviewDecision", reviewDecision)
    if (mergeable.isNotEmpty()) put("mergeable", mergeable)
    requestedFromMe?.let { put("requestedFromMe", it) }
    put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
    additions?.let { put("additions", it) }
    deletions?.let { put("deletions", it) }
    changedFiles?.let { put("changedFiles", it) }
    put("commentsCount", commentsCount); put("url", url); put("state", state); put("merged", merged); put("headSha", headSha)
    put("reviewRequests", JsonArray(reviewRequests.map { JsonPrimitive(it) }))
}

// ---------------------------------------------------------------------------------------
// The repo page (`PullsPage`): `PullRequestListItem` and `pull-request-list-query.ts`.
// ---------------------------------------------------------------------------------------

data class GhUser(val login: String, val avatarUrl: String = "")
data class GhLabel(val name: String, val color: String, val description: String? = null)

data class RepoPull(
    val number: Int,
    val title: String,
    val url: String = "",
    /** `open`, `draft`, `closed` or `merged`. */
    val state: String = "open",
    val author: GhUser? = null,
    val labels: List<GhLabel> = emptyList(),
    val assignees: List<GhUser> = emptyList(),
    val milestone: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val comments: Int = 0,
    /** `success`, `failure`, `pending`; null when unknown. */
    val checks: String? = null,
    /** `approved`, `changes_requested`, `review_required`; null when none. */
    val reviewDecision: String? = null,
    val linkedIssues: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
)

data class RepoPullsPage(val totalCount: Int?, val items: List<RepoPull>, val next: String?)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.user(key: String): GhUser? = (this[key] as? JsonObject)?.let { GhUser(it.text("login").orEmpty(), it.text("avatarUrl").orEmpty()) }

fun JsonObject.asRepoPullsPage(): RepoPullsPage = RepoPullsPage(
    totalCount = int("totalCount"),
    next = text("next")?.ifBlank { null },
    items = (this["items"] as? JsonArray).orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        RepoPull(
            number = o.int("number") ?: 0,
            title = o.text("title").orEmpty(),
            url = o.text("url").orEmpty(),
            state = o.text("state") ?: "open",
            author = o.user("author"),
            labels = (o["labels"] as? JsonArray).orEmpty().mapNotNull { l -> (l as? JsonObject)?.let { GhLabel(it.text("name").orEmpty(), it.text("color").orEmpty(), it.text("description")) } },
            assignees = (o["assignees"] as? JsonArray).orEmpty().mapNotNull { a -> (a as? JsonObject)?.let { GhUser(it.text("login").orEmpty(), it.text("avatarUrl").orEmpty()) } },
            milestone = o.text("milestone")?.ifBlank { null },
            createdAt = o.text("createdAt").orEmpty(),
            updatedAt = o.text("updatedAt").orEmpty(),
            comments = o.int("comments") ?: 0,
            checks = o.text("checks")?.ifBlank { null },
            reviewDecision = o.text("reviewDecision")?.ifBlank { null },
            linkedIssues = o.int("linkedIssues"),
            additions = o.int("additions"),
            deletions = o.int("deletions"),
            changedFiles = o.int("changedFiles"),
        )
    },
)

object PullListQuery {
    val sorts = listOf("recently-updated", "least-recently-updated", "newest", "oldest", "most-commented", "least-commented")

    private val comparators: Map<String, Comparator<RepoPull>> = mapOf(
        "recently-updated" to Comparator { a, b -> b.updatedAt.compareTo(a.updatedAt) },
        "least-recently-updated" to Comparator { a, b -> a.updatedAt.compareTo(b.updatedAt) },
        "newest" to Comparator { a, b -> b.createdAt.compareTo(a.createdAt) },
        "oldest" to Comparator { a, b -> a.createdAt.compareTo(b.createdAt) },
        "most-commented" to Comparator { a, b -> (b.comments - a.comments).takeIf { it != 0 } ?: b.updatedAt.compareTo(a.updatedAt) },
        "least-commented" to Comparator { a, b -> (a.comments - b.comments).takeIf { it != 0 } ?: b.updatedAt.compareTo(a.updatedAt) },
    )

    fun sort(items: List<RepoPull>, sort: String): List<RepoPull> = items.sortedWith(comparators[sort] ?: comparators.getValue("recently-updated"))

    private fun searchText(item: RepoPull): String =
        (listOf(item.title, "#${item.number}", item.author?.login) + item.labels.map { it.name }).filter { !it.isNullOrEmpty() }.joinToString(" ")

    /** fzf's default (`basicMatch`, smart case) fuzzy match over title, `#number`, author and label names; blank keeps everything. */
    fun filter(items: List<RepoPull>, query: String): List<RepoPull> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return items
        val caseSensitive = trimmed != trimmed.lowercase()
        return items.filter { fuzzyMatch(searchText(it), trimmed, caseSensitive) }
    }

    /** Every pattern character appears in order. */
    fun fuzzyMatch(text: String, pattern: String, caseSensitive: Boolean = false): Boolean {
        val haystack = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) pattern else pattern.lowercase()
        var at = 0
        for (ch in needle) {
            at = haystack.indexOf(ch, at)
            if (at < 0) return false
            at += 1
        }
        return true
    }
}

// ---------------------------------------------------------------------------------------
// `labelStyle` from `@antfu/design/utils/color.ts`: GitHub label chips in OKLCH, with the
// CSS Color 4 gamut mapping colorjs.io applies on the way back to sRGB.
// ---------------------------------------------------------------------------------------

data class LabelColors(val text: Int, val background: Int, val border: Int)

object LabelStyle {
    private val cache = HashMap<String, LabelColors>()

    /** ARGB ints for a label's `rrggbb` color; null when it does not parse. */
    fun of(hex: String, dark: Boolean): LabelColors? = cache.getOrPut("$hex|$dark") {
        val rgb = parseHex(hex) ?: return null
        val (l, a, b) = linearToOklab(rgb.map(::toLinear))
        val rawChroma = hypot(a, b)
        var h = Math.toDegrees(atan2(b, a))
        if (h < 0) h += 360.0
        if (l.isNaN() || rawChroma < 1e-9) h = 0.0
        val c = minOf(rawChroma, 0.18)
        if (dark) LabelColors(oklchToArgb(0.80, c, h), oklchToArgb(0.22, c * 0.5, h), oklchToArgb(0.38, c * 0.7, h))
        else LabelColors(oklchToArgb(0.42, c, h), oklchToArgb(0.94, c * 0.35, h), oklchToArgb(0.82, c * 0.5, h))
    }

    fun parseHex(hex: String): List<Double>? {
        val clean = hex.removePrefix("#")
        if (clean.length != 6 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0 until 3).map { clean.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }
    }

    private fun toLinear(c: Double): Double = if (abs(c) <= 0.04045) c / 12.92 else Math.copySign(((abs(c) + 0.055) / 1.055).pow(2.4), c)
    private fun toGamma(c: Double): Double = if (abs(c) > 0.0031308) Math.copySign(1.055 * abs(c).pow(1 / 2.4) - 0.055, c) else 12.92 * c

    private fun linearToOklab(rgb: List<Double>): Triple<Double, Double, Double> {
        val (r, g, b) = rgb
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun oklabToLinear(L: Double, a: Double, b: Double): DoubleArray {
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (L - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun lchToSrgb(l: Double, c: Double, h: Double): DoubleArray {
        val rad = Math.toRadians(h)
        return oklabToLinear(l, c * cos(rad), c * sin(rad)).map(::toGamma).toDoubleArray()
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -0.000075 && it <= 1.000075 }
    private fun clip(rgb: DoubleArray) = rgb.map { it.coerceIn(0.0, 1.0) }.toDoubleArray()

    private fun srgbToOklab(rgb: DoubleArray): Triple<Double, Double, Double> = linearToOklab(rgb.map(::toLinear))

    private fun deltaEOK(x: DoubleArray, y: DoubleArray): Double {
        val (l1, a1, b1) = srgbToOklab(x)
        val (l2, a2, b2) = srgbToOklab(y)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /** CSS Color 4 gamut mapping: lower OKLCH chroma until the clipped color is within a JND. */
    private fun mapToGamut(l: Double, c: Double, h: Double): DoubleArray {
        if (l >= 1) return doubleArrayOf(1.0, 1.0, 1.0)
        if (l <= 0) return doubleArrayOf(0.0, 0.0, 0.0)
        val origin = lchToSrgb(l, c, h)
        if (inGamut(origin)) return origin
        val jnd = 0.02
        val epsilon = 0.0001
        var current = origin
        var clipped = clip(current)
        if (deltaEOK(clipped, current) < jnd) return clipped
        var min = 0.0
        var max = c
        var minInGamut = true
        while (max - min > epsilon) {
            val chroma = (min + max) / 2
            current = lchToSrgb(l, chroma, h)
            if (minInGamut && inGamut(current)) { min = chroma; continue }
            clipped = clip(current)
            val e = deltaEOK(clipped, current)
            if (e < jnd) {
                if (jnd - e < epsilon) return clipped
                minInGamut = false
                min = chroma
            } else {
                max = chroma
            }
        }
        return clip(current)
    }

    fun oklchToArgb(l: Double, c: Double, h: Double): Int {
        val rgb = mapToGamut(l, c, h).map { (it.coerceIn(0.0, 1.0) * 255).roundToInt() }
        return (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
}
