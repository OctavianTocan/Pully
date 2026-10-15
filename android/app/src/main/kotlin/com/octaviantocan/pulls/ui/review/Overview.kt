@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.DETAILS_INFO
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
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

/** `PrActor`. */
data class PrActor(val login: String, val avatarUrl: String? = null)

private fun JsonObject?.actor(): PrActor? = this?.strOrNull("login")?.let { PrActor(it, strOrNull("avatarUrl")) }

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

fun parsePrOverviewData(element: JsonElement?): PrOverviewData? {
    val root = element as? JsonObject ?: return null
    val overview = parsePrOverview(root.objOrNull("overview") ?: return null) ?: return null
    val mergeability = root.objOrNull("mergeability")?.let {
        PrMergeability(it.bool("allowed"), it.bool("ready"), it.strOrNull("blocker"), it.strOrNull("warning"))
    } ?: assessMergeability(overview)
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
        state = o.str("state").ifEmpty { "open" },
        mergedAt = o.strOrNull("mergedAt"),
        closedAt = o.strOrNull("closedAt"),
        mergeable = o.str("mergeable").ifEmpty { "unknown" },
        mergeStateStatus = o.str("mergeStateStatus").ifEmpty { "unknown" },
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

/** Port of `assessMergeability`: whether and how the viewer can merge, from GitHub's merge state. */
fun assessMergeability(o: PrOverview): PrMergeability {
    if (!o.isOpen || !o.viewer.canPush || o.merge.methods.isEmpty()) return PrMergeability(allowed = false, ready = false)
    if (o.state == "draft" || o.mergeStateStatus == "draft") return PrMergeability(true, false, "draft")
    if (o.mergeable == "conflicting" || o.mergeStateStatus == "dirty") return PrMergeability(true, false, "conflicts")
    if (o.mergeStateStatus == "blocked" || o.mergeStateStatus == "behind") {
        return if (o.viewer.canMergeAsAdmin) PrMergeability(true, true, o.mergeStateStatus, "bypass") else PrMergeability(true, false, o.mergeStateStatus)
    }
    if (o.mergeable == "unknown" || o.mergeStateStatus == "unknown") return PrMergeability(true, false, "checking")
    if (o.mergeStateStatus == "unstable") return PrMergeability(true, true, warning = "unstable")
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
fun overviewRevalidateDelayMs(o: PrOverview): Long? {
    if (!o.isOpen) return null
    if (o.mergeable == "unknown" || o.mergeStateStatus == "unknown") return 3_000
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
fun formatTimeAgo(time: Long, now: Long = System.currentTimeMillis()): String {
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

fun prIsoMillis(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

fun prTimeAgo(iso: String?, now: Long = System.currentTimeMillis()): String = prIsoMillis(iso)?.let { formatTimeAgo(it, now) }.orEmpty()

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
    val hue = if (chroma < 1e-6) Double.NaN else Math.toDegrees(atan2(bb, aa)).let { if (it < 0) it + 360 else it }
    return Triple(ll, chroma, hue)
}

private fun oklchToLinear(l: Double, c: Double, h: Double): DoubleArray {
    val rad = Math.toRadians(h)
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

// --- PrHtml: GitHub's sanitized bodyHTML as Markdown for the app's Markdown renderer ---

private sealed interface HtmlNode
private data class HtmlText(val text: String) : HtmlNode
private class HtmlElement(val tag: String, val attrs: Map<String, String>, val children: MutableList<HtmlNode> = mutableListOf()) : HtmlNode

private val VOID_TAGS = setOf("br", "img", "input", "hr", "meta", "source", "wbr", "col", "area", "link")
private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "pre", "blockquote", "table", "hr", "details", "summary", "section", "article", "markdown-accessiblity-table", "dl")
private val TOKEN = Regex("""<!--[\s\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s=>/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(/?)>""")
private val ATTR = Regex("""([^\s=>/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")

private fun parseHtml(html: String): HtmlElement {
    val root = HtmlElement("#root", emptyMap())
    val stack = ArrayDeque<HtmlElement>().apply { addLast(root) }
    var at = 0
    for (match in TOKEN.findAll(html)) {
        if (match.range.first > at) stack.last().children += HtmlText(html.substring(at, match.range.first))
        at = match.range.last + 1
        if (match.value.startsWith("<!--")) continue
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        if (closing) {
            val index = stack.indexOfLast { it.tag == tag }
            if (index > 0) while (stack.size > index) stack.removeLast()
            continue
        }
        val attrs = ATTR.findAll(match.groupValues[3]).associate { a ->
            a.groupValues[1].lowercase() to decodeEntities(a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } })
        }
        val element = HtmlElement(tag, attrs)
        stack.last().children += element
        if (tag !in VOID_TAGS && match.groupValues[4] != "/") stack.addLast(element)
    }
    if (at < html.length) stack.last().children += HtmlText(html.substring(at))
    return root
}

private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rarr" to "→", "larr" to "←", "middot" to "·", "copy" to "©", "times" to "×")

fun decodeEntities(text: String): String = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(text) { m ->
    val body = m.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        else -> NAMED_ENTITIES[body] ?: m.value
    }
}

private fun escapeMarkdown(text: String): String = buildString {
    for (ch in text) {
        if (ch in "\\`*_[]<>&~|#") append('\\')
        append(ch)
    }
}

private class MdContext(val inLink: Boolean = false, val inTable: Boolean = false)

private fun textContent(node: HtmlNode): String = when (node) {
    is HtmlText -> decodeEntities(node.text)
    is HtmlElement -> if (node.tag == "br") "\n" else node.children.joinToString("") { textContent(it) }
}

private fun inline(node: HtmlNode, ctx: MdContext): String = when (node) {
    is HtmlText -> escapeMarkdown(decodeEntities(node.text).replace(Regex("\\s+"), " "))
    is HtmlElement -> when (node.tag) {
        "br" -> if (ctx.inTable) " " else "\\\n"
        "strong", "b" -> wrap("**", node, ctx)
        "em", "i" -> wrap("*", node, ctx)
        "del", "s", "strike" -> wrap("~~", node, ctx)
        "code", "kbd", "samp", "tt" -> textContent(node).replace(Regex("\\s+"), " ").let { code ->
            if (code.isBlank()) "" else { val fence = if ('`' in code) "``" else "`"; "$fence$code$fence" }
        }
        "a" -> {
            val href = node.attrs["href"].orEmpty()
            val label = node.children.joinToString("") { inline(it, MdContext(inLink = true, inTable = ctx.inTable)) }.trim()
            // `[&_.user-mention]:font-semibold [&_.user-mention]:no-underline`
            if ("user-mention" in node.attrs["class"].orEmpty()) "**$label**"
            else if (ctx.inLink || href.isBlank() || href.startsWith("#") || label.isBlank()) label else "[$label](<${href.replace(">", "%3E")}>)"
        }
        "picture" -> {
            // `<picture><source media="(prefers-color-scheme: dark)" srcset=…><img …>`: keep both, the renderer picks by theme.
            val img = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "img" }
            val dark = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "source" && "dark" in it.attrs["media"].orEmpty() }?.attrs?.get("srcset")?.substringBefore(' ')
            val src = img?.attrs?.get("src").orEmpty()
            val alt = escapeMarkdown(img?.attrs?.get("alt").orEmpty().trim())
            if (src.isBlank()) alt else "![$alt](<$src>${if (dark != null) " \"dark:$dark\"" else ""})"
        }
        "img" -> {
            val alt = escapeMarkdown(node.attrs["alt"].orEmpty().trim())
            val src = node.attrs["src"].orEmpty()
            if (src.isBlank()) alt else "![$alt](<$src>)"
        }
        "input" -> if (node.attrs["type"] == "checkbox") (if ("checked" in node.attrs) "[x]" else "[ ]") else ""
        "clipboard-copy", "svg", "script", "style", "template" -> ""
        else -> node.children.joinToString("") { inline(it, ctx) }
    }
}

private fun wrap(marker: String, node: HtmlElement, ctx: MdContext): String {
    val inner = node.children.joinToString("") { inline(it, ctx) }
    val trimmed = inner.trim()
    if (trimmed.isEmpty()) return inner
    val lead = if (inner.startsWith(" ")) " " else ""
    val trail = if (inner.endsWith(" ")) " " else ""
    return "$lead$marker$trimmed$marker$trail"
}

private fun isBlock(node: HtmlNode) = node is HtmlElement && (node.tag in BLOCK_TAGS || node.tag == "li" || node.tag == "tr")

/** The blocks of [children], each a Markdown string, in order. */
private fun blocks(children: List<HtmlNode>): List<String> {
    val out = mutableListOf<String>()
    val run = StringBuilder()
    fun flush() {
        val text = run.toString().replace(Regex(" *\\\\\n *"), "\\\\\n").trim().trimEnd('\\').trim()
        if (text.isNotEmpty()) out += text
        run.clear()
    }
    for (child in children) {
        if (!isBlock(child)) { run.append(inline(child, MdContext())); continue }
        flush()
        out += block(child as HtmlElement)
    }
    flush()
    return out.filter { it.isNotBlank() }
}

private fun block(node: HtmlElement): List<String> = when (node.tag) {
    "h1", "h2", "h3", "h4", "h5", "h6" -> listOf("#".repeat(node.tag[1].digitToInt()) + " " + node.children.joinToString("") { inline(it, MdContext()) }.trim()).filter { it.trimEnd('#', ' ').isNotEmpty() }
    "hr" -> listOf("---")
    "pre" -> {
        val code = textContent(node).trimEnd('\n')
        val fence = if ("```" in code) "~~~" else "```"
        listOf("$fence\n$code\n$fence")
    }
    "ul", "ol" -> listOf(list(node))
    "blockquote" -> listOf(blocks(node.children).joinToString("\n\n").lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" })
    "table" -> listOfNotNull(table(node))
    "details" -> {
        // A closed `<details>`, as GitHub renders it: [DETAILS_INFO] tells the Markdown renderer to draw a disclosure.
        val summary = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "summary" }
        val title = summary?.let { textContent(it) }?.trim()?.replace("\n", " ").orEmpty().ifEmpty { "Details" }
        val body = blocks(node.children.filter { it !== summary }).joinToString("\n\n")
        val longest = Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        listOf("$fence$DETAILS_INFO $title\n$body\n$fence")
    }
    "summary" -> listOf("**" + node.children.joinToString("") { inline(it, MdContext()) }.trim() + "**")
    else -> blocks(node.children)
}

private fun list(node: HtmlElement): String {
    var number = node.attrs["start"]?.toIntOrNull() ?: 1
    val items = node.children.filterIsInstance<HtmlElement>().filter { it.tag == "li" }
    return items.joinToString("\n") { item ->
        val marker = if (node.tag == "ol") "${number++}. " else "- "
        val indent = " ".repeat(marker.length)
        val body = blocks(item.children).joinToString("\n\n").ifEmpty { " " }
        body.lines().mapIndexed { i, line -> if (i == 0) marker + line else if (line.isEmpty()) "" else indent + line }.joinToString("\n")
    }
}

private fun table(node: HtmlElement): String? {
    val rows = mutableListOf<List<String>>()
    fun collect(element: HtmlElement) {
        for (child in element.children.filterIsInstance<HtmlElement>()) {
            if (child.tag == "tr") rows += child.children.filterIsInstance<HtmlElement>().filter { it.tag == "td" || it.tag == "th" }
                .map { cell -> cell.children.joinToString("") { inline(it, MdContext(inTable = true)) }.trim().replace("\n", " ") }
            else collect(child)
        }
    }
    collect(node)
    if (rows.isEmpty()) return null
    val width = rows.maxOf { it.size }.coerceAtLeast(1)
    fun row(cells: List<String>) = "| " + (0 until width).joinToString(" | ") { cells.getOrElse(it) { "" } } + " |"
    return (listOf(row(rows.first()), "|" + " --- |".repeat(width)) + rows.drop(1).map(::row)).joinToString("\n")
}

/** GitHub's rendered (and sanitized) `bodyHTML` as GitHub-flavored Markdown with the same structure. */
fun htmlToMarkdown(html: String): String = blocks(parseHtml(html).children).joinToString("\n\n")

// ===================== Overview UI pieces =====================

internal fun Modifier.bottomBorder(color: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

internal fun Modifier.topBorder(color: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

/** `PrHtml`: GitHub's rendered `bodyHTML`, restyled like the app's Markdown (`chat-markdown`). */
@Composable
fun PrHtml(html: String, modifier: Modifier = Modifier) {
    val markdown = remember(html) { htmlToMarkdown(html) }
    Markdown(markdown, modifier, style = PullsType.sm)
}

/**
 * The header row of a timeline card. A tap folds the card up to this row and opens it again (F72), and the chevron at
 * the end says which way it will go.
 */
@Composable
private fun CardHeader(collapsed: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = LocalPullsColors.current
    // Who and what on the left, wrapping when long; when and the chevron always in the same place on the right.
    Row(
        Modifier.fillMaxWidth()
            .cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (collapsed) "Show the comment" else "Hide the comment", onClick = onToggle)
            .defaultMinSize(minHeight = TOUCH).padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
        trailing()
        LuIcon(if (collapsed) Lu.chev else Lu.chevd, tint = c.faint, size = 18.dp)
    }
}

/** Accounts that are tools, not people: GitHub apps ("name[bot]") and the usual review and CI bots. Their comments start folded (rule 91). */
internal fun isBotLogin(login: String?): Boolean {
    val name = login?.lowercase() ?: return false
    return name.endsWith("[bot]") || name in KNOWN_BOTS
}

private val KNOWN_BOTS = setOf("coderabbitai", "github-actions", "dependabot", "renovate", "vercel", "netlify", "codecov", "sonarqubecloud", "socket-security", "copilot")

/** The first line of a comment as plain words, for the dimmed line under a folded comment: no Markdown marks, links as their text. */
internal fun commentPreview(html: String): String =
    htmlToMarkdown(html).lineSequence()
        .map { line ->
            line.trim().trimStart('#', '>', '-', '*', '+', ' ')
                .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
                .replace(Regex("""\\(.)"""), "$1")
                .replace(Regex("""[`*_~]"""), "")
                .trim()
        }
        .firstOrNull { it.isNotEmpty() }.orEmpty()

/** A folded comment's first line, dimmed: enough to know what it is without opening it. It is a preview, so a long one ends in an ellipsis. */
@Composable
private fun FoldedLine(text: String) {
    if (text.isEmpty()) return
    Text(text, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), style = PullsType.sm, color = LocalPullsColors.current.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Which part of the conversation shows: all of it, what people wrote, or what bots wrote (J19). */
internal enum class Talk(val label: String) { All("All"), People("People"), Bots("Bots") }

/** Whether [item] belongs under [talk]: an event (a label, a push of the title) only under All. */
internal fun inTalk(item: PrTimelineItem, talk: Talk): Boolean = when (talk) {
    Talk.All -> true
    Talk.People -> item !is PrTimelineItem.Event && !isBotLogin(item.actor?.login)
    Talk.Bots -> item !is PrTimelineItem.Event && isBotLogin(item.actor?.login)
}

/**
 * `PrConversation`: the description card, then the timeline (last 30, "Show n earlier"). A long one is hard to get
 * around, most of it bots (J19), so chips over it narrow it to what people wrote or what bots wrote, each with how many.
 */
@Composable
fun Conversation(overview: PrOverview) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    var showAll by rememberSaveable(overview.id) { mutableStateOf(false) }
    var talk by rememberSaveable(overview.id, "talk") { mutableStateOf(Talk.All) }
    // Pushed commits live in the Commits tab (rule 91); the conversation is what people said and did.
    val everything = remember(overview.timeline) { overview.timeline.filter { it !is PrTimelineItem.Commits } }
    val counts = remember(everything) { Talk.entries.associateWith { which -> everything.count { inTalk(it, which) } } }
    val timeline = remember(everything, talk) { everything.filter { inTalk(it, talk) } }
    val earlier = if (showAll || talk != Talk.All) 0 else maxOf(0, timeline.size - 30)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only worth having when there is something to narrow: people and bots both spoke.
        if ((counts[Talk.People] ?: 0) > 0 && (counts[Talk.Bots] ?: 0) > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Talk.entries.forEach { which ->
                    SoftChip(which.label, talk == which, { talk = which }, count = if (which == Talk.All) null else "${counts[which]}")
                }
            }
        }
        var descriptionOpen by rememberSaveable(overview.id, "description") { mutableStateOf(true) }
        Column(Modifier.timelineCard(c)) {
            CardHeader(
                collapsed = !descriptionOpen, onToggle = { descriptionOpen = !descriptionOpen },
                trailing = { Text(prTimeAgo(overview.createdAt), style = PullsType.sm, color = c.muted, maxLines = 1) },
            ) {
                overview.author?.let { GithubAvatar(it.login, avatarUrl = it.avatarUrl, size = 18.dp) }
                // A long name wraps whole in its column; the age stays at the right edge (rules 2 and 91).
                Text(overview.author?.login ?: "ghost", Modifier.weight(1f, fill = false), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            }
            if (descriptionOpen) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (overview.bodyHTML.isNotBlank()) PrHtml(overview.bodyHTML)
                else Text(t("overview.noDescription"), style = PullsType.sm.copy(fontStyle = FontStyle.Italic), color = c.fade)
            } else FoldedLine(commentPreview(overview.bodyHTML))
        }
        if (overview.timelineHidden > 0) LinkText(t("overview.moreOnGithub", "n" to overview.timelineHidden)) { uri.openUri(overview.url) }
        if (earlier > 0) LinkText(t("overview.earlier", "n" to earlier)) { showAll = true }
        timeline.drop(earlier).forEach { item -> TimelineEntry(item) }
    }
}

/** A timeline card in the kit's look: the white 16dp card with the canvas shadow, no border (rules 45 and 49). */
@Composable
private fun Modifier.timelineCard(c: PullsColors): Modifier {
    val shape = RoundedCornerShape(PullsRadius.card)
    return fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).animateContentSize(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
}

/** A quiet text button on a 44dp row: the accent text of rule 96, at body size (rule 14). */
@Composable
internal fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Box(modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
    }
}

/** `PrTimelineEntry`: an event line, or a comment / review card. */
@Composable
fun TimelineEntry(item: PrTimelineItem) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    when (item) {
        // An event is a sentence: who, what and when, with no icon in front (rule 48), at body size (rule 14).
        is PrTimelineItem.Event -> FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            item.actor?.let { GithubAvatar(it.login, avatarUrl = it.avatarUrl, size = 18.dp) }
            Text(item.actor?.login ?: "ghost", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(eventText(item, t("overview.event.${item.event}")), style = PullsType.sm, color = c.muted)
            Text(prTimeAgo(item.createdAt), style = PullsType.sm, color = c.faint)
            item.message?.let { Text(it, Modifier.fillMaxWidth().padding(start = 24.dp), style = PullsType.sm, color = c.muted) }
        }
        // Pushed commits are in the Commits tab, not the conversation (rule 91).
        is PrTimelineItem.Commits -> Unit
        is PrTimelineItem.Comment, is PrTimelineItem.Review -> CommentCard(item)
    }
}

@Composable
private fun CommentCard(item: PrTimelineItem) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    var showInline by rememberSaveable(item.id + ":inline") { mutableStateOf(false) }
    val comment = item as? PrTimelineItem.Comment
    val review = item as? PrTimelineItem.Review
    val url = comment?.url ?: review?.url.orEmpty()
    val body = comment?.bodyHTML ?: review?.bodyHTML.orEmpty()
    val minimized = comment?.minimizedReason
    // A comment GitHub hid, or one a bot wrote, starts folded up; any other starts open (rule 91). Either folds and opens with a tap on its header.
    var open by rememberSaveable(item.id, "open") { mutableStateOf(minimized == null && !isBotLogin(item.actor?.login)) }
    val collapsed = !open
    Column(Modifier.timelineCard(c)) {
        CardHeader(
            collapsed = collapsed, onToggle = { open = !open },
            trailing = {
                Text(prTimeAgo(item.createdAt), Modifier.cueClickable(enabled = url.isNotBlank()) { uri.openUri(url) }, style = PullsType.sm, color = c.muted, maxLines = 1)
            },
        ) {
            item.actor?.let { GithubAvatar(it.login, avatarUrl = it.avatarUrl, size = 18.dp) }
            // A comment can only be a comment, so it carries no verb; a review says what it decided, in the colour of
            // the decision instead of an icon before the name (rule 48).
            val verb = when (review?.state) {
                null -> null
                "approved" -> t("overview.timeline.approved") to StatusColors.green(c.dark)
                "changes_requested" -> t("overview.timeline.changesRequested") to StatusColors.red(c.dark)
                else -> t("overview.timeline.reviewed") to c.muted
            }
            val hidden = minimized?.let { t("overview.timeline.hidden", "reason" to it) }
            // The name, the decision and a hidden note read as one line that wraps whole, so none of them is ever cut; the
            // age stays at the right edge (rules 2 and 91).
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.text)) { append(softBreaks(item.actor?.login ?: "ghost")) }
                    verb?.let { (words, color) -> append("  "); withStyle(SpanStyle(color = color)) { append(words) } }
                    hidden?.let { append("  "); withStyle(SpanStyle(color = c.muted)) { append(it) } }
                },
                Modifier.weight(1f, fill = false),
                style = PullsType.sm,
            )
        }
        if (body.isNotBlank() && !collapsed) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { PrHtml(body) }
        if (collapsed && body.isNotBlank()) FoldedLine(commentPreview(body))
        if (review != null && review.commentCount > 0 && !collapsed) {
            Column(Modifier.fillMaxWidth().topBorder(c.borderMute).padding(start = 14.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).cueClickable { showInline = !showInline },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tc("overview.timeline.inlineComments", review.commentCount), Modifier.weight(1f), style = PullsType.sm, color = c.muted)
                    LuIcon(if (showInline) Lu.chevd else Lu.chev, tint = c.faint, size = 18.dp)
                }
                if (showInline) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.comments.forEach { inline ->
                        Row {
                            Box(Modifier.width(2.dp).height(1.dp))
                            Column(
                                Modifier.fillMaxWidth().drawBehind { drawLine(c.border, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        inline.path + (inline.line?.let { ":$it" } ?: ""),
                                        Modifier.cueClickable(enabled = inline.url.isNotBlank()) { uri.openUri(inline.url) },
                                        style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted,
                                    )
                                    // A word, not a bordered tag (rule 49).
                                    if (inline.outdated) Text(t("overview.timeline.outdated"), style = PullsType.sm, color = c.amber)
                                }
                                PrHtml(inline.bodyHTML)
                            }
                        }
                    }
                    if (review.commentCount > review.comments.size) LinkText(t("overview.moreOnGithub", "n" to review.commentCount - review.comments.size)) { uri.openUri(review.url) }
                }
            }
        }
    }
}

/**
 * The `<i18n-t>` slots of an event: `{label}` as a label-colored chip, `{subject}` medium
 * (mono for a merge commit), `{from}`/`{to}` mono (medium for a renamed title), all in
 * `color-base` against the faded sentence.
 */
@Composable
private fun eventText(item: PrTimelineItem.Event, template: String): AnnotatedString {
    val c = LocalPullsColors.current
    val label = item.labelColor?.let { labelColors(it, c.dark) }
    return remember(item, template, c.dark) {
        buildAnnotatedString {
            var at = 0
            for (m in Regex("""\{(\w+)\}""").findAll(template)) {
                append(template.substring(at, m.range.first))
                at = m.range.last + 1
                when (m.groupValues[1]) {
                    "label" -> if (item.labelName != null) withStyle(
                        SpanStyle(
                            color = label?.let { Color(it.text) } ?: c.text, background = label?.let { Color(it.background) } ?: c.code,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) { append("\u2009${item.labelName}\u2009") }
                    "subject" -> withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Medium, fontFamily = if (item.event == "merged") PullsType.mono else null)) { append(item.subject.orEmpty()) }
                    "from" -> withStyle(slotStyle(item, c.text)) { append(item.from.orEmpty()) }
                    "to" -> withStyle(slotStyle(item, c.text)) { append(item.to.orEmpty()) }
                    else -> append(m.value)
                }
            }
            append(template.substring(at))
        }
    }
}

private fun slotStyle(item: PrTimelineItem.Event, text: Color) =
    if (item.event == "renamed") SpanStyle(color = text, fontWeight = FontWeight.Medium) else SpanStyle(color = text, fontFamily = PullsType.mono)
