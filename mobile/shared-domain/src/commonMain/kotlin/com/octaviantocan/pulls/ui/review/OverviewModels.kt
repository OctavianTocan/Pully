package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.pullTimestampMillis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Typed view of the `pr-overview` answer (`{ overview, mergeability, defaultCommits }`), with
 * field names from `core/src/providers/github/overview.ts`, plus the pure logic the PR page
 * top runs on it: `commit-selection.ts`, `assessMergeability`, `DisplayDate`'s relative time,
 * `labelStyle` (label chip colors) and a `PrHtml` stand-in that turns GitHub's `bodyHTML`
 * into Markdown so headings, lists, links and code keep their structure.
 */

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.texts(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** `PrActor`. [bot] is GitHub's word that the account is an app or a bot, not a person (`isBotActor` on the server, N31). */
data class PrActor(val login: String, val avatarUrl: String? = null, val bot: Boolean = false)

private fun JsonObject?.actor(): PrActor? = this?.strOrNull("login")?.let { PrActor(it, strOrNull("avatarUrl"), bool("bot")) }

/** `PrCheck`. `bucket`: failing, pending, passing, skipped. */
data class PrCheck(
    val id: String,
    val name: String,
    val group: String?,
    val bucket: String,
    val conclusion: String,
    val summary: String?,
    val url: String?,
    val required: Boolean,
)

/** `PrChecksSummary`. `state`: success, failure, pending, or null without a rollup. */
data class PrChecksSummary(val failing: Int, val pending: Int, val passing: Int, val skipped: Int, val total: Int, val hidden: Int, val state: String?)

/** `PrCommit`. */
data class PrCommit(
    val oid: String,
    val abbreviatedOid: String,
    val headline: String,
    val body: String = "",
    val committedDate: String = "",
    val url: String = "",
    val authorName: String? = null,
    val authorLogin: String? = null,
    val authorAvatarUrl: String? = null,
    val checks: String? = null,
)

/** `PrTimelineReviewComment`. */
data class PrReviewComment(val id: String, val url: String, val path: String, val line: Int?, val outdated: Boolean, val bodyHTML: String)

/** `PrTimelineCommit`. */
data class PrTimelineCommit(val oid: String, val abbreviatedOid: String, val headline: String, val url: String)

/** `PrTimelineItem`: a comment, a review, a run of pushed commits, or an event. */
sealed interface PrTimelineItem {
    val id: String
    val createdAt: String
    val actor: PrActor?

    data class Comment(override val id: String, override val createdAt: String, override val actor: PrActor?, val url: String, val bodyHTML: String, val minimizedReason: String?) : PrTimelineItem

    data class Review(
        override val id: String,
        override val createdAt: String,
        override val actor: PrActor?,
        val url: String,
        val state: String,
        val bodyHTML: String,
        val comments: List<PrReviewComment>,
        val commentCount: Int,
    ) : PrTimelineItem

    data class Commits(override val id: String, override val createdAt: String, override val actor: PrActor?, val commits: List<PrTimelineCommit>) : PrTimelineItem

    data class Event(
        override val id: String,
        override val createdAt: String,
        override val actor: PrActor?,
        val event: String,
        val labelName: String?,
        val labelColor: String?,
        val subject: String?,
        val from: String?,
        val to: String?,
        val message: String?,
    ) : PrTimelineItem
}

/** `PrViewerAbilities`. */
data class PrViewer(
    val permission: String?,
    val canPush: Boolean,
    val canMergeAsAdmin: Boolean,
    val canClose: Boolean,
    val canReopen: Boolean,
    val canUpdate: Boolean,
    val canUpdateBranch: Boolean,
    val canEnableAutoMerge: Boolean = false,
    val canDisableAutoMerge: Boolean = false,
    /** He wrote it. Only then are its title and description his to edit (K21). */
    val isAuthor: Boolean = false,
) {
    /** The title and the description are editable on his own pull requests alone, however much GitHub allows (K21). */
    val canEditText: Boolean get() = canUpdate && isAuthor
}

/** `PrMergeSettings` (the commit title/message settings stay on the server: it sends `defaultCommits`). */
data class PrMergeSettings(val methods: List<String>, val defaultMethod: String)

/** `PrMergeability`. `blocker`: draft, conflicts, blocked, behind, checking. `warning`: bypass, unstable. */
data class PrMergeability(val allowed: Boolean, val ready: Boolean, val blocker: String? = null, val warning: String? = null)

/** `PrOverview`: everything the PR page shows besides the diff. */
data class PrOverview(
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val body: String,
    val bodyHTML: String,
    val createdAt: String,
    val author: PrActor?,
    /** open, draft, closed, merged. */
    val state: String,
    val mergedAt: String?,
    val closedAt: String?,
    /** mergeable, conflicting, unknown. */
    val mergeable: String,
    val mergeStateStatus: String,
    /** approved, changes_requested, review_required, or null. */
    val reviewDecision: String?,
    val headOid: String,
    val headRef: String,
    val headOwner: String?,
    val baseOid: String,
    val baseRef: String,
    val requestedReviewers: List<String>,
    val latestReviews: List<Pair<PrActor, String>>,
    val checks: List<PrCheck>,
    val checksSummary: PrChecksSummary,
    /** Oldest first. */
    val commits: List<PrCommit>,
    val commitsHidden: Int,
    /** Oldest first. */
    val timeline: List<PrTimelineItem>,
    val timelineHidden: Int,
    val viewer: PrViewer,
    val merge: PrMergeSettings,
    /** The method it will merge with once its checks pass, while it is set to; null otherwise. */
    val autoMergeMethod: String? = null,
) {
    val isOpen: Boolean get() = state == "open" || state == "draft"

    /** Comments and reviews: the count on the conversation tab and the header chip. */
    val conversationCount: Int get() = timeline.count { it is PrTimelineItem.Comment || it is PrTimelineItem.Review }
}

/** The whole `pr-overview` answer. */
data class PrOverviewData(val overview: PrOverview, val mergeability: PrMergeability, val defaultCommits: Map<String, Pair<String, String>>)

fun parsePrOverviewData(element: JsonElement?, fresh: Boolean = true, expectedHead: String? = null, observedState: String? = null): PrOverviewData? {
    val root = element as? JsonObject ?: return null
    val parsed = parsePrOverview(root.objOrNull("overview") ?: return null) ?: return null
    val overview = parsed.copy(state = resolvedPrState(parsed.state, observedState, fresh))
    val assessed = assessMergeability(overview)
    val reported = root.objOrNull("mergeability")?.let {
        PrMergeability(it.bool("allowed"), it.bool("ready"), it.strOrNull("blocker"), it.strOrNull("warning"))
    }
    // A cached/computed verdict never overrides GitHub's raw conflicts or an unknown merge state.
    val current = if (!assessed.ready) assessed else reported ?: assessed
    val currentHead = expectedHead.isNullOrBlank() || expectedHead == overview.headOid
    val mergeability = if ((!fresh || !currentHead) && current.allowed && current.ready) {
        current.copy(ready = false, blocker = "checking", warning = null)
    } else current
    val defaults = root.objOrNull("defaultCommits").orEmpty().mapNotNull { (method, value) ->
        (value as? JsonObject)?.let { method to (it.str("title") to it.str("body")) }
    }.toMap()
    return PrOverviewData(overview, mergeability, defaults)
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())


fun parsePrOverview(o: JsonObject): PrOverview? {
    if (o.str("id").isEmpty()) return null
    val head = o.objOrNull("head").orEmpty()
    val base = o.objOrNull("base").orEmpty()
    val summary = o.objOrNull("checksSummary").orEmpty()
    val viewer = o.objOrNull("viewer").orEmpty()
    val merge = o.objOrNull("merge").orEmpty()
    val methods = merge.texts("methods")
    return PrOverview(
        id = o.str("id"),
        number = o.int("number"),
        title = o.str("title"),
        url = o.str("url"),
        body = o.str("body"),
        bodyHTML = o.str("bodyHTML"),
        createdAt = o.str("createdAt"),
        author = o.objOrNull("author").actor(),
        state = normalizedPrState(o.str("state"), o.strOrNull("mergedAt"), o.strOrNull("closedAt")),
        mergedAt = o.strOrNull("mergedAt"),
        closedAt = o.strOrNull("closedAt"),
        mergeable = normalizedMergeable(o.str("mergeable")),
        mergeStateStatus = normalizedMergeState(o.str("mergeStateStatus")),
        reviewDecision = o.strOrNull("reviewDecision"),
        headOid = head.str("oid"),
        headRef = head.str("ref"),
        headOwner = head.strOrNull("owner"),
        baseOid = base.str("oid"),
        baseRef = base.str("ref"),
        requestedReviewers = o.texts("requestedReviewers"),
        latestReviews = o.objects("latestReviews").mapNotNull { r -> r.objOrNull("author").actor()?.let { it to r.str("state") } },
        checks = o.objects("checks").map { c ->
            PrCheck(c.str("id"), c.str("name"), c.strOrNull("group"), c.str("bucket"), c.str("conclusion"), c.strOrNull("summary"), c.strOrNull("url"), c.bool("required"))
        },
        checksSummary = PrChecksSummary(
            summary.int("failing"), summary.int("pending"), summary.int("passing"), summary.int("skipped"),
            summary.int("total"), summary.int("hidden"), summary.strOrNull("state"),
        ),
        commits = o.objects("commits").map { c ->
            val author = c.objOrNull("author").orEmpty()
            PrCommit(
                c.str("oid"), c.str("abbreviatedOid").ifEmpty { c.str("oid").take(7) }, c.str("headline"), c.str("body"), c.str("committedDate"), c.str("url"),
                author.strOrNull("name"), author.strOrNull("login"), author.strOrNull("avatarUrl"), c.strOrNull("checks"),
            )
        },
        commitsHidden = o.int("commitsHidden"),
        timeline = o.objects("timeline").mapNotNull(::parseTimelineItem),
        timelineHidden = o.int("timelineHidden"),
        viewer = PrViewer(
            viewer.strOrNull("permission"), viewer.bool("canPush"), viewer.bool("canMergeAsAdmin"), viewer.bool("canClose"),
            viewer.bool("canReopen"), viewer.bool("canUpdate"), viewer.bool("canUpdateBranch"),
            viewer.bool("canEnableAutoMerge"), viewer.bool("canDisableAutoMerge"), viewer.bool("isAuthor"),
        ),
        merge = PrMergeSettings(methods, merge.str("defaultMethod").takeIf { it in methods } ?: methods.firstOrNull() ?: "merge"),
        autoMergeMethod = o.objOrNull("autoMerge")?.strOrNull("method"),
    )
}

private fun parseTimelineItem(o: JsonObject): PrTimelineItem? {
    val id = o.str("id")
    val createdAt = o.str("createdAt")
    val actor = o.objOrNull("actor").actor()
    return when (o.str("kind")) {
        "comment" -> PrTimelineItem.Comment(id, createdAt, actor, o.str("url"), o.str("bodyHTML"), o.strOrNull("minimizedReason"))
        "review" -> PrTimelineItem.Review(
            id, createdAt, actor, o.str("url"), o.str("state"), o.str("bodyHTML"),
            o.objects("comments").map { c -> PrReviewComment(c.str("id"), c.str("url"), c.str("path"), c.intOrNull("line"), c.bool("outdated"), c.str("bodyHTML")) },
            o.int("commentCount"),
        )
        "commits" -> PrTimelineItem.Commits(id, createdAt, actor, o.objects("commits").map { c -> PrTimelineCommit(c.str("oid"), c.str("abbreviatedOid"), c.str("headline"), c.str("url")) })
        "event" -> {
            val label = o.objOrNull("label")
            PrTimelineItem.Event(id, createdAt, actor, o.str("event"), label?.strOrNull("name"), label?.strOrNull("color"), o.strOrNull("subject"), o.strOrNull("from"), o.strOrNull("to"), o.strOrNull("message"))
        }
        else -> null
    }
}

/** GitHub's lifecycle, with a merge remaining final even when another field still says closed or draft. */
fun normalizedPrState(state: String?, mergedAt: String? = null, closedAt: String? = null): String = when {
    !mergedAt.isNullOrBlank() || state.equals("merged", ignoreCase = true) -> "merged"
    state.equals("closed", ignoreCase = true) || !closedAt.isNullOrBlank() -> "closed"
    state.equals("draft", ignoreCase = true) -> "draft"
    state.equals("open", ignoreCase = true) -> "open"
    else -> "unknown"
}

/** A fresh overview wins over a kept diff, except that an observed merge is irreversible. */
fun resolvedPrState(overview: String, observed: String?, fresh: Boolean): String {
    val state = normalizedPrState(overview)
    val other = normalizedPrState(observed)
    return when {
        state == "merged" || other == "merged" -> "merged"
        fresh || other == "unknown" -> state
        else -> other
    }
}

private fun normalizedMergeable(value: String): String = value.lowercase().takeIf { it in setOf("mergeable", "conflicting") } ?: "unknown"
private fun normalizedMergeState(value: String): String = value.lowercase().takeIf {
    it in setOf("clean", "dirty", "draft", "blocked", "behind", "unstable", "has_hooks")
} ?: "unknown"

/** GitHub's merge blocker, independent of the viewer's permissions and the checks' result. */
fun overviewMergeBlocker(o: PrOverview): String? {
    if (!o.isOpen) return null
    val mergeable = normalizedMergeable(o.mergeable)
    val status = normalizedMergeState(o.mergeStateStatus)
    return when {
        mergeable == "conflicting" || status == "dirty" -> "conflicts"
        o.state == "draft" || status == "draft" -> "draft"
        mergeable == "unknown" || status == "unknown" -> "checking"
        status == "blocked" || status == "behind" -> status
        else -> null
    }
}

/** Port of `assessMergeability`: whether and how the viewer can merge, from GitHub's merge state. */
fun assessMergeability(o: PrOverview): PrMergeability {
    if (!o.isOpen || !o.viewer.canPush || o.merge.methods.isEmpty()) return PrMergeability(allowed = false, ready = false)
    val blocker = overviewMergeBlocker(o)
    if (blocker != null) {
        return if (blocker in setOf("blocked", "behind") && o.viewer.canMergeAsAdmin) {
            PrMergeability(true, true, blocker, "bypass")
        } else PrMergeability(true, false, blocker)
    }
    if (normalizedMergeState(o.mergeStateStatus) == "unstable") return PrMergeability(true, true, warning = "unstable")
    return PrMergeability(true, true)
}

/** The PR state actions `PrMergeBox` offers the viewer, in its order: `change` to its icon. */
fun stateActions(o: PrOverview): List<Pair<String, String>> = buildList {
    if (o.state == "draft" && o.viewer.canUpdate) add("ready" to "eye-duotone")
    if (o.state == "open" && o.viewer.canUpdate) add("draft" to "pencil-simple-duotone")
    if (o.isOpen && o.viewer.canClose) add("close" to "x-circle-duotone")
    if (o.state == "closed" && o.viewer.canReopen) add("reopen" to "arrow-counter-clockwise-duotone")
}

/**
 * How long until the overview is worth fetching again on its own (`revalidateDelay` in
 * `pr-overview-store.ts`): GitHub computes mergeability lazily, and pending checks settle.
 */
fun overviewRevalidateDelayMs(data: PrOverviewData): Long? {
    if (data.overview.isOpen && data.mergeability.blocker == "checking") return 3_000
    return overviewRevalidateDelayMs(data.overview)
}

fun overviewRevalidateDelayMs(o: PrOverview): Long? {
    if (!o.isOpen) return null
    if (normalizedMergeable(o.mergeable) == "unknown" || normalizedMergeState(o.mergeStateStatus) == "unknown") return 3_000
    return if (o.checksSummary.pending > 0) 30_000 else null
}

// --- commit-selection.ts ---

/** A contiguous run of a PR's commits, both ends inclusive; one commit when `from == to`. */
data class CommitSelection(val from: String, val to: String) {
    val pair: Pair<String, String> get() = from to to
}

private const val SHORT_SHA_LENGTH = 12

private fun indexOfSha(commits: List<PrCommit>, sha: String): Int = commits.indexOfFirst { it.oid.startsWith(sha) }

/** Where a selection sits in the PR's commits (oldest first): inclusive indexes, or null when an end is unknown. */
fun resolveCommitSelection(commits: List<PrCommit>, selection: CommitSelection): IntRange? {
    val a = indexOfSha(commits, selection.from)
    val b = indexOfSha(commits, selection.to)
    if (a < 0 || b < 0) return null
    return minOf(a, b)..maxOf(a, b)
}

/** The selection spanning two positions, in either order, with shas shortened like the web URL. */
fun selectCommitRange(commits: List<PrCommit>, a: Int, b: Int = a): CommitSelection {
    val first = commits[minOf(a, b)]
    val last = commits[maxOf(a, b)]
    return CommitSelection(first.oid.take(SHORT_SHA_LENGTH), last.oid.take(SHORT_SHA_LENGTH))
}

fun selectCommit(oid: String): CommitSelection = oid.take(SHORT_SHA_LENGTH).let { CommitSelection(it, it) }

/** The single commits just before and after a selection; null at either end or when it is not among [commits]. */
fun adjacentCommits(commits: List<PrCommit>, selection: CommitSelection): Pair<CommitSelection?, CommitSelection?> {
    val range = resolveCommitSelection(commits, selection) ?: return null to null
    return commits.getOrNull(range.first - 1)?.let { selectCommit(it.oid) } to commits.getOrNull(range.last + 1)?.let { selectCommit(it.oid) }
}

/**
 * `PrCommitList.pick`: a plain tap picks one commit (or clears it when it is already the
 * only one picked); an extending tap (shift-click on the web, long-press on the phone)
 * spans from the anchor to [index].
 */
fun pickCommit(commits: List<PrCommit>, current: CommitSelection?, anchor: Int?, index: Int, extend: Boolean): CommitSelection? {
    val range = current?.let { resolveCommitSelection(commits, it) }
    if (extend) return selectCommitRange(commits, anchor ?: range?.first ?: index, index)
    val alreadyOnlyThis = range != null && range.first == index && range.last == index
    return if (alreadyOnlyThis) null else selectCommitRange(commits, index)
}

// --- DisplayDate (formatTimeAgo in @antfu/design/utils/format.ts) ---

private const val SECOND = 1000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val MONTH = 30 * DAY
private const val YEAR = 365 * DAY
private val TIME_UNITS = listOf(Triple(MINUTE, SECOND, "s"), Triple(HOUR, MINUTE, "min"), Triple(DAY, HOUR, "h"), Triple(MONTH, DAY, "d"), Triple(YEAR, MONTH, "mo"), Triple(Long.MAX_VALUE, YEAR, "y"))

/** `"3 d ago"`, `"in 2 h"`, `"just now"`, as the web's `DisplayDate` prints it. */
fun formatTimeAgo(time: Long, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): String {
    val delta = now - time
    val absolute = abs(delta)
    if (absolute < SECOND) return "just now"
    for ((limit, divisor, unit) in TIME_UNITS) {
        if (absolute < limit) {
            val value = (absolute.toDouble() / divisor).roundToLong()
            return if (delta >= 0) "$value $unit ago" else "in $value $unit"
        }
    }
    return ""
}

fun prIsoMillis(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let(::pullTimestampMillis)

fun prTimeAgo(iso: String?, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): String = prIsoMillis(iso)?.let { formatTimeAgo(it, now) }.orEmpty()

// --- labelStyle (@antfu/design/utils/color.ts): OKLCH stops, chroma clamped to 0.18 ---

/** Text, background and border colors (ARGB) of a GitHub label chip, as the web derives them. */
data class LabelColors(val text: Long, val background: Long, val border: Long)

fun labelColors(hex: String, dark: Boolean): LabelColors {
    val (_, rawChroma, rawHue) = srgbToOklch(parseHex(hex))
    val h = if (rawHue.isFinite()) rawHue else 0.0
    val c = minOf(if (rawChroma.isFinite()) rawChroma else 0.0, 0.18)
    return if (dark) LabelColors(oklchToArgb(0.80, c, h), oklchToArgb(0.22, c * 0.5, h), oklchToArgb(0.38, c * 0.7, h))
    else LabelColors(oklchToArgb(0.42, c, h), oklchToArgb(0.94, c * 0.35, h), oklchToArgb(0.82, c * 0.5, h))
}

private fun parseHex(hex: String): DoubleArray {
    val clean = hex.removePrefix("#").let { if (it.length == 3) it.map { ch -> "$ch$ch" }.joinToString("") else it }.padEnd(6, '0')
    return DoubleArray(3) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16) / 255.0 }
}

private fun toLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
private fun fromLinear(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

private fun srgbToOklch(rgb: DoubleArray): Triple<Double, Double, Double> {
    val (r, g, b) = rgb.map(::toLinear)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val aa = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    val chroma = sqrt(aa * aa + bb * bb)
    val hue = if (chroma < 1e-6) Double.NaN else (atan2(bb, aa) / (PI / 180.0)).let { if (it < 0) it + 360 else it }
    return Triple(ll, chroma, hue)
}

private fun oklchToLinear(l: Double, c: Double, h: Double): DoubleArray {
    val rad = (h * (PI / 180.0))
    val a = c * cos(rad)
    val b = c * sin(rad)
    val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return doubleArrayOf(
        4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
        -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
        -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
    )
}

/**
 * OKLCH to opaque sRGB with the CSS Color 4 gamut mapping colorjs.io's `toGamut` runs:
 * binary-search the chroma, accepting a clipped color once it is within the OKLab JND (0.02).
 */
private fun oklchToArgb(l: Double, c: Double, h: Double): Long {
    fun inGamut(rgb: DoubleArray) = rgb.all { it in -1e-6..1.000001 }
    fun clip(rgb: DoubleArray) = DoubleArray(3) { rgb[it].coerceIn(0.0, 1.0) }
    fun deltaEOK(a: DoubleArray, b: DoubleArray): Double {
        val x = linearToOklab(a)
        val y = linearToOklab(b)
        return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
    }
    val result: DoubleArray = when {
        l >= 1.0 -> doubleArrayOf(1.0, 1.0, 1.0)
        l <= 0.0 -> doubleArrayOf(0.0, 0.0, 0.0)
        else -> {
            val origin = oklchToLinear(l, c, h)
            if (inGamut(origin)) origin
            else {
                val jnd = 0.02
                val epsilon = 0.0001
                var clipped = clip(origin)
                if (deltaEOK(clipped, origin) < jnd) clipped
                else {
                    var min = 0.0
                    var max = c
                    var minInGamut = true
                    var answer: DoubleArray? = null
                    while (max - min > epsilon) {
                        val chroma = (min + max) / 2
                        val current = oklchToLinear(l, chroma, h)
                        if (minInGamut && inGamut(current)) { min = chroma; continue }
                        clipped = clip(current)
                        val e = deltaEOK(clipped, current)
                        if (e < jnd) {
                            if (jnd - e < epsilon) { answer = clipped; break }
                            minInGamut = false
                            min = chroma
                        } else max = chroma
                    }
                    answer ?: clipped
                }
            }
        }
    }
    val (r, g, b) = result.map { (fromLinear(it.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255) }
    return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

private fun linearToOklab(rgb: DoubleArray): DoubleArray {
    val (r, g, b) = rgb.toList()
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}
