package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.core.listedPullByNumber
import com.octaviantocan.pulls.core.searchNumber
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.asPulls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    /** [place]: the thing on its GitHub page the link pointed at, a comment, a file or the checks (`LinkPlaces`). */
    data class Target(val target: OpenTarget, val place: LinkPlace? = null) : HomeLink
    data class Repo(val owner: String, val repo: String) : HomeLink
}

object HomeLinks {
    private val github = Regex("""github\.com/([^/\s]+)/([^/\s#?]+)(?:/(pull|commit|compare)/([^\s#?]+)|/pulls/?)?(?:[/?#]|$)""")

    /** A GitHub pull request, commit, compare or repository URL; otherwise whatever [PrLinks] understands (`owner/repo#1`, a pulls.review link). */
    fun parse(text: String): HomeLink? {
        val trimmed = text.trim()
        github.find(trimmed)?.let { match ->
            val (owner, repo, kind, rest) = match.destructured
            val place = LinkPlaces.parse(trimmed)
            return when (kind) {
                "pull" -> Regex("""^\d+""").find(rest)?.value?.let { HomeLink.Target(OpenTarget.Pull(owner, repo, it.toInt(), "github.com"), place) }
                "commit" -> HomeLink.Target(OpenTarget.Commit(owner, repo, rest.replace(Regex("/.*$"), "")), place)
                "compare" -> splitRange(rest)?.let { (base, head) -> HomeLink.Target(OpenTarget.Compare(owner, repo, base, head), place) }
                else -> HomeLink.Repo(owner, repo)
            }
        }
        return PrLinks.parse(trimmed)?.let { HomeLink.Target(it) }
    }

    private val githubUrl = Regex("""(?:https?://)?(?:www\.)?github\.com/[^\s<>"'()]+""", RegexOption.IGNORE_CASE)
    private val bareRepo = Regex("""^(?:https?://)?(?:www\.)?github\.com/([^/\s#?]+)/([^/\s#?]+?)(?:\.git)?(?:/pulls)?/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    /**
     * What a link handed over by another app opens: text shared, selected or tapped. Every GitHub address in it is read
     * whole, a pull request, commit or compare before a repository, and an issue, file or any other GitHub page is not a
     * repository link. Text without a GitHub address falls back to [PrLinks] (`owner/repo#1`, a server link).
     */
    fun parseShared(text: String): HomeLink? {
        val urls = githubUrl.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.toList()
        if (urls.isEmpty()) return PrLinks.parse(text)?.let { HomeLink.Target(it) }
        urls.firstNotNullOfOrNull { url -> parse(url) as? HomeLink.Target }?.let { return it }
        return urls.firstNotNullOfOrNull { url -> bareRepo.matchEntire(url)?.let { HomeLink.Repo(it.groupValues[1], it.groupValues[2]) } }
    }

    /**
     * Whether a link tapped inside Pully opens in Pully: a GitHub pull request, commit or compare link, or a page on Pully's
     * own server (a posted diff, a pull request), the ones a tap in another app hands to Pully (the manifest's link filters).
     * Anything else, a GitHub issue or file and a server page Pully has no screen for included, opens outside.
     */
    fun opensInPully(url: String): Boolean {
        val trimmed = url.trim()
        if (githubPage.containsMatchIn(trimmed)) return parse(trimmed) is HomeLink.Target
        if (serverPage.containsMatchIn(trimmed)) return parseShared(trimmed) != null
        return false
    }

    private val githubPage = Regex("""^https?://(?:www\.)?github\.com/[^/\s]+/[^/\s]+/(?:pull|commit|compare)/""", RegexOption.IGNORE_CASE)
    private val serverPage = Regex("""^https://prs\.octaviantocan\.com/""", RegexOption.IGNORE_CASE)

    /**
     * What the search pill's text opens: a link [parse] understands, or a bare number (`42`, `#42`) that only one of the
     * [listed] pull requests has. `null` when the text is just a search.
     */
    fun fromSearch(text: String, listed: List<ListedPull>): HomeLink? {
        val trimmed = text.trim()
        searchNumber(trimmed)?.let { number ->
            return listedPullByNumber(number, listed)?.let { HomeLink.Target(OpenTarget.Pull(it.owner, it.repo, it.number, "github.com")) }
        }
        return parse(trimmed)
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

/** `my-pulls-cached`'s answer. [jev]: the server sorts pull requests with Jev (`classifier: "jev"`), so rows carry `jev`. */
data class PullsSnapshot(val pulls: List<PullRow>, val fetchedAt: Long, val refreshing: Boolean = false, val jev: Boolean = false)

fun JsonObject.asSnapshot(): PullsSnapshot? {
    val at = (this["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: return null
    return PullsSnapshot(
        asPulls(), at, (this["refreshing"] as? JsonPrimitive)?.contentOrNull == "true",
        jev = (this["classifier"] as? JsonPrimitive)?.contentOrNull == "jev",
    )
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
    /**
     * The server's copy, without the server refreshing it from GitHub however old it is; for [followJev]. With `classify`
     * the server has Jev sort the rows it has not sorted yet.
     */
    private val peek: suspend (state: String, classify: Boolean) -> PullsSnapshot? = { state, _ -> cached(state) },
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

    /**
     * The server sorts pull requests with Jev, so the Inbox may group them by kind and sort them by risk. Only
     * `my-pulls-cached` says so: a snapshot without `classifier` turns it off, a plain `my-pulls` list leaves it as it was.
     */
    var jev by mutableStateOf(false)
        private set

    private var state = "open"
    private var generation = 0

    /** Shows the device copy for [state] at once, then loads. */
    suspend fun select(state: String) {
        this.state = state
        val stored = readStored(state)
        pulls = stored?.pulls
        fetchedAt = stored?.fetchedAt
        stored?.let { jev = it.jev }
        load(false)
    }

    /** A fresh list even when the current one is recent. */
    suspend fun reload() = load(true)

    /**
     * The Inbox is sorted by risk or grouped by kind, which need Jev's word on every open row. Jev is asked only then:
     * otherwise a row shows a kind and a risk once a review of it had Jev read it.
     */
    var wantsJev by mutableStateOf(false)

    /** Jev is on, the Inbox needs it ([wantsJev]), and open rows on screen have no kind yet: the server is sorting them. */
    val sorting: Boolean get() = jev && wantsJev && state == "open" && pulls.orEmpty().any { it.state == "open" && it.jev == null }

    /**
     * While [sorting], the server's copy is read at once and then every [everyMs], for [forMs] at most, without the
     * server asking GitHub; each read asks Jev to sort what it has not. Only the kinds are taken from it, by row at the
     * same head and title: the list on screen can be newer than that copy, and a load replaces the list.
     */
    suspend fun followJev(everyMs: Long = 3_000, forMs: Long = 120_000) {
        val until = now() + forMs
        var first = true
        while (sorting && now() + everyMs <= until) {
            if (!first) delay(everyMs)
            first = false
            val current = generation
            val selected = state
            val server = try { peek(selected, true) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            val shown = pulls
            if (current != generation || shown == null) continue
            jev = server.jev
            val sorted = server.pulls.filter { it.jev != null }.associateBy { Triple(it.owner, it.repo, it.number) }
            val filled = shown.map { row ->
                val match = sorted[Triple(row.owner, row.repo, row.number)]?.takeIf { row.jev == null && it.headSha == row.headSha && it.title == row.title }
                match?.let { row.copy(jev = it.jev) } ?: row
            }
            if (filled == shown) continue
            pulls = filled
            writeStored(selected, PullsSnapshot(filled, fetchedAt ?: server.fetchedAt, jev = jev))
        }
    }

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
            // Whether the server runs Jev is news even when its list is older than the one on screen.
            if (server != null && isCurrent()) jev = server.jev
            if (server != null) show(server)
            if (!force && server != null && !server.refreshing && now() - server.fetchedAt < freshForMs) return
            val list = fresh(selected)
            show(PullsSnapshot(list, now(), jev = jev))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent()) {
                error = e.message ?: e.toString()
                com.octaviantocan.pulls.data.Analytics.error("inbox.load", e.message)
            }
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
                if (value.jev) put("classifier", "jev")
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
    if (baseRef.isNotEmpty()) put("baseRef", baseRef)
    if (headRef.isNotEmpty()) put("headRef", headRef)
    stack?.let { stack ->
        put("stack", buildJsonObject {
            put("id", stack.id); put("position", stack.position); put("size", stack.size); put("native", stack.native)
            stack.below?.let { put("below", it) }
        })
    }
    jev?.let { jev ->
        put("jev", buildJsonObject { put("kind", jev.kind); put("kindConfidence", jev.kindConfidence); put("risk", jev.risk) })
    }
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
