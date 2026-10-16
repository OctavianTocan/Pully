package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AnalyticsSummary
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.engineName
import com.octaviantocan.pulls.data.asAnalyticsSummary
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.t
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** The ranges the server counts over, in days. */
private val RANGES = listOf(7, 30, 90)

/**
 * The last answer for each range, kept on the phone, so the page opens on it at once, after a restart too, and refreshes
 * underneath (rule 128); [warm] fetches the first range as Pulls starts, so a first open does not wait either ("still
 * long loading times ... that should be snappy": recording 16 Oct 13:06, 04:49 to 04:55; O10).
 */
internal object StatsKeep {
    private const val PREFS = "pulls-stats"
    private val kept = HashMap<Int, AnalyticsSummary>()
    private var warmed = false

    fun get(context: android.content.Context, days: Int): AnalyticsSummary? = kept[days] ?: runCatching {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("days:$days", null)
            ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).asAnalyticsSummary() }
    }.getOrNull()?.also { kept[days] = it }

    suspend fun fetch(context: android.content.Context, repo: Repo, days: Int): AnalyticsSummary {
        val raw = repo.analyticsSummaryRaw(days)
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("days:$days", raw.toString()).apply()
        return raw.asAnalyticsSummary().also { kept[days] = it }
    }

    /** Once per launch, in the background, when usage stats are on. */
    suspend fun warm(context: android.content.Context) {
        if (warmed || !AppSettings.get(context).usageStats) return
        warmed = true
        runCatching { fetch(context, Repo(context), RANGES.first()) }
    }
}

private sealed interface StatsLoad {
    data object Waiting : StatsLoad
    data object Failed : StatsLoad
    data class Ready(val summary: AnalyticsSummary) : StatsLoad
}

/**
 * Settings, Stats: what our server counted over the last 7, 30 or 90 days (`analytics-summary`). The totals sit in
 * label and value columns (rule 22), a bar a day shows sessions or pull requests opened, then where time goes, what
 * the AI did and cost, the slowest calls, GitHub, stored diffs and what went wrong. Until the first answer the rows
 * stand in as skeletons (rule 51).
 */
@Composable
internal fun ColumnScope.StatsPage(settings: AppSettings) {
    val context = LocalContext.current
    if (!settings.usageStats) {
        StatsNote(t("stats.off"))
        return
    }
    val repo = remember { Repo(context) }
    var range by rememberSaveable { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    val days = RANGES[range]
    var load by remember(days) { mutableStateOf<StatsLoad>(StatsKeep.get(context, days)?.let { StatsLoad.Ready(it) } ?: StatsLoad.Waiting) }
    LaunchedEffect(days, attempt) {
        load = try {
            StatsLoad.Ready(StatsKeep.fetch(context, repo, days))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A page already showing an answer keeps it; only an empty one says it could not load.
            if (load is StatsLoad.Ready) load else StatsLoad.Failed
        }
    }

    Group(t("stats.range")) {
        Box(Modifier.padding(top = 10.dp)) {
            CapsuleTabs(RANGES.map { t("stats.days", "n" to it) }, range, { range = it }, Modifier.fillMaxWidth())
        }
    }
    when (val now = load) {
        StatsLoad.Waiting -> StatsSkeleton()
        StatsLoad.Failed -> {
            StatsNote(t("stats.loadFailed"), error = true)
            PillButton(t("common.retry"), { attempt++ }, Modifier.align(Alignment.CenterHorizontally))
        }
        is StatsLoad.Ready -> if (now.summary.empty) StatsNote(t("stats.empty")) else StatsBody(now.summary, settings)
    }
}

@Composable
private fun StatsBody(summary: AnalyticsSummary, settings: AppSettings) {
    val locale = remember(settings.locale) { Locale.forLanguageTag(settings.locale) }
    val totals = summary.totals
    SheetCard(gap = 24.dp) {
        StatLine(t("stats.sessions"), StatsFormat.count(totals.sessions, locale), first = true)
        StatLine(t("stats.prsOpened"), StatsFormat.count(totals.prsOpened, locale))
        StatLine(t("stats.filesReviewed"), StatsFormat.count(totals.filesReviewed, locale))
        StatLine(t("stats.aiRuns"), StatsFormat.count(totals.aiRuns, locale))
        StatLine(t("stats.aiSpend"), StatsFormat.usd(totals.costUsd))
        StatLine(t("stats.crashes"), StatsFormat.count(totals.crashes, locale))
        StatLine(t("stats.errors"), StatsFormat.count(totals.errors, locale))
    }

    AiUsage(summary, locale)

    if (summary.days.isNotEmpty()) {
        var series by rememberSaveable { mutableIntStateOf(0) }
        Group(t("stats.perDay")) {
            Box(Modifier.padding(top = 10.dp)) {
                CapsuleTabs(listOf(t("stats.sessions"), t("stats.prsOpened")), series, { series = it }, Modifier.fillMaxWidth())
            }
            SheetCard {
                DayBars(
                    summary.days.map { if (series == 0) it.sessions else it.prsOpened },
                    first = StatsFormat.day(summary.days.first().day, locale),
                    last = StatsFormat.day(summary.days.last().day, locale),
                )
            }
        }
    }

    StatsTable(
        t("stats.screens"), listOf(t("stats.views"), t("stats.time")),
        summary.screens.map { StatRow(StatsFormat.screen(it.screen), null, listOf(StatsFormat.count(it.views, locale), StatsFormat.seconds(it.seconds))) },
    )
    StatsTable(
        t("stats.slowest"), listOf(t("stats.calls"), t("stats.median"), t("stats.p95")),
        summary.calls.take(SLOWEST).map { StatRow(it.name, null, listOf(StatsFormat.count(it.calls, locale), StatsFormat.ms(it.medianMs), StatsFormat.ms(it.p95Ms))) },
    )
    StatsTable(
        t("stats.speed"), listOf(t("stats.median"), t("stats.p95")),
        summary.speed.map { StatRow(it.metric, it.app.ifBlank { null }, listOf(StatsFormat.ms(it.medianMs), StatsFormat.ms(it.p95Ms))) },
    )
    val github = summary.github
    if (github.calls > 0) Group(t("stats.github")) {
        SheetCard {
            StatLine(t("stats.calls"), StatsFormat.count(github.calls, locale), first = true)
            StatLine(t("stats.failed"), StatsFormat.count(github.failures, locale))
            github.lowestRateLeft?.let { StatLine(t("stats.rateLeft"), StatsFormat.count(it, locale)) }
        }
    }
    val cache = summary.cache
    if (cache.hit + cache.refetch + cache.stale > 0) Group(t("stats.storedDiffs")) {
        SheetCard {
            StatLine(t("stats.hit"), StatsFormat.count(cache.hit, locale), first = true)
            StatLine(t("stats.refetch"), StatsFormat.count(cache.refetch, locale))
            StatLine(t("stats.stale"), StatsFormat.count(cache.stale, locale))
        }
    }
    if (summary.errors.isNotEmpty()) Group(t("stats.problems")) {
        SheetCard {
            summary.errors.forEachIndexed { index, problem ->
                SheetRow(
                    problem.kind.ifBlank { problem.name }, null, first = index == 0, iconless = true,
                    detail = listOf(problem.app, problem.message).filter { it.isNotBlank() }.joinToString(": ").ifBlank { null },
                ) {
                    Text(StatsFormat.count(problem.count, locale), style = PullsType.num, color = LocalPullsColors.current.text)
                }
            }
        }
    }
}

/**
 * What the AI spent: the tokens and the cost over the range, then by feature (tours, summaries, analysis, Ask) and by
 * model, each model with a bar of its share, the costliest first ("a section where we see all the usage and consumption
 * that we've done of tokens by generating stuff through the tours and the summaries and the analysis and then a
 * breakdown of which models cost more": recording 16 Oct 13:06, 04:18 to 04:48; O9). A run whose engine reported no
 * cost (Codex, a proxy) is not shown as free: its cost reads "–" and a line under the card says so.
 */
@Composable
internal fun AiUsage(summary: AnalyticsSummary, locale: Locale) {
    if (summary.totals.aiRuns == 0L) return
    val c = LocalPullsColors.current
    val tasks = summary.tasks
    val models = summary.engines.sortedWith(compareByDescending<AnalyticsSummary.EngineUse> { it.costUsd }.thenByDescending { it.tokens })
    val unpriced = models.any { it.priced < it.runs }
    fun cost(usd: Double, runs: Long, priced: Long) = if (priced == 0L && runs > 0L) "–" else StatsFormat.usd(usd)
    Group(t("stats.aiUsage")) {
        SheetCard {
            val totals = summary.totals
            val cacheRead = models.sumOf { it.cacheRead }
            SheetRow(
                t("stats.tokens"), null, first = true, iconless = true,
                detail = listOfNotNull(
                    "${StatsFormat.tokens(totals.tokensIn)} in",
                    "${StatsFormat.tokens(totals.tokensOut)} out",
                    cacheRead.takeIf { it > 0 }?.let { "${StatsFormat.tokens(it)} from cache" },
                ).joinToString(" · "),
            ) { Text(StatsFormat.tokens(totals.tokensIn + totals.tokensOut), style = PullsType.num, color = c.text, maxLines = 1) }
            StatLine(t("stats.aiSpend"), cost(totals.costUsd, totals.aiRuns, models.sumOf { it.priced }))
        }
        if (unpriced) Text(t("stats.unpriced"), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = PullsType.sm, color = c.muted)
    }
    StatsTable(
        t("stats.byFeature"), listOf(t("stats.runs"), t("stats.tokens"), t("stats.cost")),
        tasks.map { StatRow(StatsFormat.feature(it.task), null, listOf(StatsFormat.count(it.runs, locale), StatsFormat.tokens(it.tokens), cost(it.costUsd, it.runs, it.priced))) },
    )
    if (models.isEmpty()) return
    // Each model's share of the spend, or of the tokens when nothing reported a cost, as a bar under its name.
    val byCost = models.sumOf { it.costUsd } > 0.0
    val whole = if (byCost) models.sumOf { it.costUsd } else models.sumOf { it.tokens }.toDouble().coerceAtLeast(1.0)
    Group(t("stats.byModel")) {
        SheetCard {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                listOf(t("stats.tokens"), t("stats.cost")).forEach { Text(it, Modifier.width(COLUMN), style = PullsType.sm, color = c.muted, textAlign = TextAlign.End, maxLines = 1) }
            }
            models.forEach { model ->
                val share = ((if (byCost) model.costUsd else model.tokens.toDouble()) / whole).toFloat().coerceIn(0f, 1f)
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp)
                        .drawBehind { drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.model.ifBlank { engineName(model.engine) }, style = PullsType.row, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${engineName(model.engine)} · ${com.octaviantocan.pulls.ui.design.tc("stats.runsCount", model.runs.toInt())}", style = PullsType.sm, color = c.muted, maxLines = 1)
                        Canvas(Modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = "${Math.round(share * 100)}%" }) {
                            val radius = CornerRadius(size.height / 2)
                            drawRoundRect(c.paper2, cornerRadius = radius)
                            if (share > 0f) drawRoundRect(c.accentTeal, size = Size((size.width * share).coerceAtLeast(size.height), size.height), cornerRadius = radius)
                        }
                    }
                    Text(StatsFormat.tokens(model.tokens), Modifier.width(COLUMN), style = PullsType.num, color = c.text, textAlign = TextAlign.End, maxLines = 1)
                    Text(cost(model.costUsd, model.runs, model.priced), Modifier.width(COLUMN), style = PullsType.num, color = c.text, textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

/** How many of the slowest calls are listed. */
private const val SLOWEST = 10

/** The width of a value column, the same on every row so the numbers line up whatever their length (rule 13). */
private val COLUMN = 68.dp

/** A label and its value on one row, the value in Mona Sans; not tappable. */
@Composable
private fun StatLine(label: String, value: String, first: Boolean = false) {
    SheetRow(label, null, first = first, iconless = true) {
        Text(value, style = PullsType.num, color = LocalPullsColors.current.text, maxLines = 1)
    }
}

private data class StatRow(val name: String, val sub: String?, val values: List<String>)

/** A titled card of rows: a name (with a quieter second line) and values under fixed column labels. */
@Composable
private fun StatsTable(title: String, columns: List<String>, rows: List<StatRow>) {
    if (rows.isEmpty()) return
    val c = LocalPullsColors.current
    Group(title) {
        SheetCard {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                columns.forEach { Text(it, Modifier.width(COLUMN), style = PullsType.sm, color = c.muted, textAlign = TextAlign.End, maxLines = 2) }
            }
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                        .drawBehind { drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.name, style = PullsType.row, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.sub?.let { Text(it, style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    row.values.forEach { Text(it, Modifier.width(COLUMN), style = PullsType.num, color = c.text, textAlign = TextAlign.End, maxLines = 1) }
                }
            }
        }
    }
}

/** One bar a day, oldest on the left, scaled to the busiest day; a day with none keeps a hairline so the run of days reads. */
@Composable
private fun DayBars(values: List<Long>, first: String, last: String) {
    val c = LocalPullsColors.current
    val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = values.joinToString(", ") }) {
            val n = values.size.coerceAtLeast(1)
            val gap = (if (n > 45) 1.dp else 2.dp).toPx()
            val width = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val radius = CornerRadius(PullsRadius.xs.toPx().coerceAtMost(width / 2))
            values.forEachIndexed { index, value ->
                val x = index * (width + gap)
                if (value <= 0L) {
                    drawRect(c.borderMute, Offset(x, size.height - 1.dp.toPx()), Size(width, 1.dp.toPx()))
                } else {
                    val height = (size.height * value / peak).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(c.accentTeal, Offset(x, size.height - height), Size(width, height), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(first, Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1)
            Text(last, style = PullsType.sm, color = c.muted, maxLines = 1)
        }
    }
}

/** The totals' rows while the first answer is on its way: their shape, no words (rules 51 and 85). */
@Composable
private fun StatsSkeleton() {
    SheetCard(gap = 24.dp) {
        repeat(7) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Skeleton(Modifier.fillMaxWidth(0.42f))
                Skeleton(Modifier.width(44.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.StatsNote(text: String, error: Boolean = false) {
    val c = LocalPullsColors.current
    Text(
        text, Modifier.padding(horizontal = 16.dp, vertical = 20.dp).align(Alignment.CenterHorizontally),
        style = PullsType.sm, color = if (error) c.error else c.muted, textAlign = TextAlign.Center,
    )
}

/** How the Stats page writes its numbers. */
internal object StatsFormat {
    fun count(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    /** Dollars to the cent; a spend under a cent that is not nothing says so rather than reading as nothing. */
    fun usd(value: Double): String = when {
        value <= 0.0 -> "$0.00"
        value < 0.01 -> "<$0.01"
        else -> "$" + String.format(Locale.US, "%,.2f", value)
    }

    /** Tokens as a short count: 950, 12.4k, 3.1M. */
    fun tokens(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(Locale.US, if (value < 100_000) "%.1fk" else "%.0fk", value / 1_000.0)
        else -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    }

    /** What a run was for, from the server's one word, as the feature it belongs to. */
    fun feature(task: String): String = when (task) {
        "tour" -> "Tours"
        "brief" -> "Summaries"
        "analyze" -> "Analysis"
        "review" -> "AI review"
        "ask" -> "Ask"
        "chat" -> "Conversation"
        else -> task.replaceFirstChar { it.titlecase() }
    }

    /** A time in seconds: whole seconds under a minute, whole minutes under an hour, then hours to a tenth. */
    fun seconds(value: Double): String = when {
        value < 60 -> "${Math.round(value)} s"
        value < 3_600 -> "${Math.round(value / 60)} min"
        else -> String.format(Locale.US, "%.1f h", value / 3_600)
    }

    /** A duration in milliseconds: whole milliseconds under a second, then seconds to a tenth. */
    fun ms(value: Double): String = if (value < 1_000) "${Math.round(value)} ms" else String.format(Locale.US, "%.1f s", value / 1_000)

    /** `2026-10-15` as the day and the month in the app's language. */
    fun day(value: String, locale: Locale): String =
        runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM", locale)) }.getOrDefault(value)

    /** A screen's fixed name (`home`, `settings-stats`) as words: `Home`, `Settings stats`. */
    fun screen(name: String): String = name.replace('-', ' ').replaceFirstChar { it.titlecase() }
}
