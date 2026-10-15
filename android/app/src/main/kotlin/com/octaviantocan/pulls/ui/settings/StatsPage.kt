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

/** The last answer for each range in this run, so the page opens on it at once and refreshes underneath (rule 128). */
private val kept = HashMap<Int, AnalyticsSummary>()

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
    var load by remember(days) { mutableStateOf<StatsLoad>(kept[days]?.let { StatsLoad.Ready(it) } ?: StatsLoad.Waiting) }
    LaunchedEffect(days, attempt) {
        load = try {
            StatsLoad.Ready(repo.analyticsSummary(days).also { kept[days] = it })
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
        t("stats.tasks"), listOf(t("stats.runs"), t("stats.failed"), t("stats.cost")),
        summary.tasks.map { StatRow(it.task, null, listOf(StatsFormat.count(it.runs, locale), StatsFormat.count(it.failures, locale), StatsFormat.usd(it.costUsd))) },
    )
    StatsTable(
        t("stats.engines"), listOf(t("stats.runs"), t("stats.failed"), t("stats.cost")),
        summary.engines.map {
            StatRow(engineName(it.engine), it.model.ifBlank { null }, listOf(StatsFormat.count(it.runs, locale), StatsFormat.count(it.failures, locale), StatsFormat.usd(it.costUsd)))
        },
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
