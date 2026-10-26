package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.settings.SettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Only model choices; no URL or credential is retained in picker state. */
data class ModelConfiguration(val engine: String, val model: String)

/** Real server catalogs and per-engine choices, scoped to the page's source owner. */
interface ModelSettingsServices {
    fun current(): Boolean
    fun configuration(): ModelConfiguration
    fun save(configuration: ModelConfiguration)
    fun remembered(engine: String): String?
    fun remember(engine: String, model: String)
    fun cached(engine: String): CliCatalog?
    suspend fun fetch(engine: String, refresh: Boolean = false): Result<CliCatalog>
    /** Whether [engine]'s CLI is signed in: null when not known yet or for an engine with no sign-in. */
    fun signedIn(engine: String): Boolean? = null
    /** Asks the server which CLIs are signed in, for [signedIn]. */
    suspend fun checkSignIns() {}
}

/** Native prefs use an opaque source namespace supplied by the real account root. */
class SourceModelSettingsServices(
    private val repo: Repo,
    private val preferences: SettingsPreferences,
    private val read: () -> ModelConfiguration,
    private val write: (ModelConfiguration) -> Unit,
    private val owner: () -> Boolean,
) : ModelSettingsServices {
    private val opening = runCatching { repo.source() }
    override fun current(): Boolean = owner() && opening.fold(
        onSuccess = repo::isCurrent,
        onFailure = { runCatching { repo.source() }.isFailure },
    )
    private fun requireCurrent() { if (!current()) throw CancellationException("The model settings source changed.") }
    override fun configuration(): ModelConfiguration { requireCurrent(); return read() }
    override fun save(configuration: ModelConfiguration) { requireCurrent(); write(configuration) }
    override fun remembered(engine: String): String? { requireCurrent(); return preferences.getString("model:$engine", null) }
    override fun remember(engine: String, model: String) { requireCurrent(); preferences.edit().putString("model:$engine", model).apply() }
    override fun cached(engine: String): CliCatalog? {
        if (opening.isFailure || !current()) return null
        return preferences.getString("catalog:$engine", null)?.let { raw ->
            runCatching { (Json.parseToJsonElement(raw) as? JsonObject)?.asCatalog() }.getOrNull()
        }
    }
    override fun signedIn(engine: String): Boolean? = if (current()) knownAiSignIn(repo, engine) else null
    override suspend fun checkSignIns() { if (current()) checkAiSignIns(repo) }
    override suspend fun fetch(engine: String, refresh: Boolean): Result<CliCatalog> = try {
        requireCurrent()
        opening.getOrThrow()
        val raw = repo.models(engine, refresh)
        requireCurrent()
        preferences.edit().putString("catalog:$engine", raw.toString()).apply()
        Result.success(raw.asCatalog())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        requireCurrent()
        Result.failure(failure)
    }
}
