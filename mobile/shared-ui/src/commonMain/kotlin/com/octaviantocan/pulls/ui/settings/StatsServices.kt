package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.AnalyticsSummary
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.asAnalyticsSummary
import com.octaviantocan.pulls.settings.SettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** The ranges counted by the existing analytics-summary RPC. */
internal val STATS_RANGES = listOf(7, 30, 90)

/** Actual kept summaries and RPCs, owned by the account/server/Access source opening Stats. */
interface StatsServices {
    fun current(): Boolean
    fun cached(days: Int): AnalyticsSummary?
    suspend fun fetch(days: Int): AnalyticsSummary
}

/** The native root supplies preferences scoped by its opaque, eligible source namespace. */
class SourceStatsServices(
    private val repo: Repo,
    private val preferences: SettingsPreferences,
    private val enabled: () -> Boolean,
    private val owner: () -> Boolean,
) : StatsServices {
    private val opening = runCatching { repo.source() }
    private val kept = mutableMapOf<Int, AnalyticsSummary>()
    private val requests = mutableMapOf<Int, Long>()
    override fun current(): Boolean = owner() && opening.fold(
        onSuccess = repo::isCurrent,
        // The current owner can show invalid saved configuration as an actionable load failure.
        onFailure = { runCatching { repo.source() }.isFailure },
    )

    override fun cached(days: Int): AnalyticsSummary? {
        if (opening.isFailure || !current() || !enabled()) return null
        return kept[days] ?: runCatching {
            preferences.getString("days:$days", null)?.let { Json.parseToJsonElement(it).asAnalyticsSummary() }
        }.getOrNull()?.also { kept[days] = it }
    }

    override suspend fun fetch(days: Int): AnalyticsSummary {
        requireCurrent()
        val request = (requests[days] ?: 0L) + 1L
        requests[days] = request
        val raw = repo.analyticsSummaryRaw(days)
        requireCurrent()
        val summary = raw.asAnalyticsSummary()
        if (requests[days] == request) {
            preferences.edit().putString("days:$days", raw.toString()).apply()
            kept[days] = summary
        }
        return summary
    }

    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!current() || !enabled()) throw CancellationException("The usage statistics source changed or usage stats were turned off.")
        opening.getOrThrow()
    }
}

internal sealed interface StatsLoad {
    data object Waiting : StatsLoad
    data object Failed : StatsLoad
    data class Ready(val summary: AnalyticsSummary) : StatsLoad
}

/** This is the actual page state; old captured range/retry callbacks cannot act through a new owner. */
internal class StatsPageState(private val services: StatsServices, initialRange: Int) {
    private var revision by mutableLongStateOf(0L)
    var range by mutableIntStateOf(initialRange)
        private set
    var attempt by mutableIntStateOf(0)
        private set
    var load by mutableStateOf<StatsLoad>(kept(range))
    fun select(next: Int) {
        if (!services.current() || next !in STATS_RANGES.indices || next == range) return
        revision++
        range = next
        load = kept(next)
    }
    fun retry() { if (services.current()) { revision++; attempt++ } }
    private fun kept(range: Int): StatsLoad = services.cached(STATS_RANGES[range])?.let(StatsLoad::Ready) ?: StatsLoad.Waiting

    fun request(): Pair<Int, Long> = range to revision

    suspend fun refresh(snapshot: Pair<Int, Long> = request()) {
        val (openingRange, openingRevision) = snapshot
        val previous = load
        fun current() = services.current() && revision == openingRevision && range == openingRange
        if (!current()) return
        try {
            val summary = services.fetch(STATS_RANGES[openingRange])
            currentCoroutineContext().ensureActive()
            if (current()) load = StatsLoad.Ready(summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            // Only this range/attempt may retain its answer or publish a quiet-refresh failure.
            if (current()) load = if (previous is StatsLoad.Ready) previous else StatsLoad.Failed
        }
    }
}

@Composable
internal fun rememberStatsPageState(services: StatsServices): StatsPageState {
    var savedRange by rememberSaveable { mutableIntStateOf(0) }
    val page = remember(services) { StatsPageState(services, savedRange.coerceIn(STATS_RANGES.indices)) }
    savedRange = page.range
    val request = page.request()
    LaunchedEffect(page, request) {
        page.refresh(request)
    }
    return page
}
