package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.ENGINES
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices

/** Actual picker state, including its immediate kept list and source-owned search input. */
internal class ModelPageState(private val services: ModelSettingsServices) {
    var config by mutableStateOf(services.configuration())
        private set
    var attempt by mutableStateOf(0)
    var query by mutableStateOf("")
    var produced by mutableStateOf<Pair<String, Result<CliCatalog>>?>(services.cached(config.engine)?.let { config.engine to Result.success(it) })
    val loaded: Result<CliCatalog>? get() = produced?.takeIf { it.first == config.engine }?.second
    fun save(next: ModelConfiguration): Boolean {
        if (!services.current()) return false
        services.save(next)
        if (next.engine != config.engine) query = ""
        config = next
        return true
    }
}

@Composable
internal fun rememberModelPageState(services: ModelSettingsServices): ModelPageState {
    val page = remember(services) { ModelPageState(services) }
    val engine = page.config.engine
    val attempt = page.attempt
    // Keep the original engine-tagged producer: a late previous-engine list is never actionable.
    LaunchedEffect(page, engine, attempt) {
        val cached = if (attempt == 0) services.cached(engine) else null
        page.produced = cached?.let { engine to Result.success(it) }
        val fresh = services.fetch(engine, refresh = attempt > 0)
        if (cached == null || fresh.isSuccess) page.produced = engine to fresh
    }
    LaunchedEffect(page) {
        ENGINES.filter { it != engine && services.cached(it) == null }.forEach { services.fetch(it) }
    }
    return page
}
