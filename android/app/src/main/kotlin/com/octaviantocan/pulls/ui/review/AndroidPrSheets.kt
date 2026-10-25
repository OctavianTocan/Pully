package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.home.LocalNativeHomeActions
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.settings.asCatalog
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices
import kotlinx.coroutines.CancellationException

/**
 * The model lists the server reported, kept on the phone as well as for this run, so the model sheet shows its names at
 * once every time it opens, the first time after a restart too, and only refreshes them quietly underneath ("the models
 * cannot be loading", recording 15 Oct 15:37, 12:07 to 12:24; L22). [warm] reads the kept lists once.
 */
internal object ModelCatalogs {
    private val cache = HashMap<String, CliCatalog>()
    private var prefs: android.content.SharedPreferences? = null
    private var owner: String? = null
    fun get(engine: String): CliCatalog? = synchronized(cache) { cache[engine] }
    fun put(engine: String, catalog: CliCatalog) { synchronized(cache) { cache[engine] = catalog } }

    /** Reads the lists this phone kept, once a run, before a sheet asks for one. */
    fun warm(context: android.content.Context) {
        synchronized(cache) {
            val next = com.octaviantocan.pulls.data.NativeWorkspaceBinding.active?.namespace ?: "existing-server"
            if (prefs != null && owner == next) return
            cache.clear()
            owner = next
            val kept = context.applicationContext.getSharedPreferences(if (next == "existing-server") PREFS else "$PREFS-workspace-$next", android.content.Context.MODE_PRIVATE)
            prefs = kept
            kept.all.forEach { (engine, raw) ->
                if (engine in cache || raw !is String) return@forEach
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject }.getOrNull()
                    ?.let { cache[engine] = it.asCatalog() }
            }
        }
    }

    /** Keeps the server's answer for [engine] for this run and on the phone. */
    fun keep(engine: String, raw: kotlinx.serialization.json.JsonObject) {
        put(engine, raw.asCatalog())
        prefs?.edit()?.putString(engine, raw.toString())?.apply()
    }

    private const val PREFS = "model_catalogs"
}

/** `ai-models` for [engine], kept ([ModelCatalogs]); the failure comes back as a value so the sheet can say why. */
internal suspend fun fetchCatalog(repo: Repo, engine: String, refresh: Boolean = false): Result<CliCatalog> = try {
    val raw = repo.models(engine, refresh)
    ModelCatalogs.keep(engine, raw)
    Result.success(raw.asCatalog())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** The original Android configuration facade for the shared display policy. */
internal fun analysisModelLabel(config: ServerConfig, catalog: CliCatalog?, effort: String, messages: Messages): String =
    analysisModelLabel(com.octaviantocan.pulls.ui.settings.ModelConfiguration(config.engine, config.model), catalog, effort, messages)


@Composable
internal fun PrSheet(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, start: PrPage, onClose: () -> Unit, onReview: (String?) -> Unit, ui: DiffUi? = null, file: ReviewFile? = null) {
    val models = rememberAndroidModelSettingsServices()
    val native = LocalNativeHomeActions.current
    val services = remember(models, native) { PrSheetServices(models, native) { pull, canFollow -> com.octaviantocan.pulls.notifications.ChecksWatchRow(pull, canFollow) } }
    PrSheet(session, top, data, head, start, services, onClose, onReview, ui, file)
}

@Composable
internal fun FindingsSheet(session: ReviewSession, onClose: () -> Unit) = FindingsSheet(session, rememberAndroidModelSettingsServices(), onClose)
