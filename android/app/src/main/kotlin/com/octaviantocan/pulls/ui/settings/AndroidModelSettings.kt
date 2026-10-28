package com.octaviantocan.pulls.ui.settings

import android.content.Context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.aikit.CliCatalog
import com.octaviantocan.aikit.asCatalog
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Android retains its actual kept catalogs, per-engine memory and ServerStore keys. */
@Composable
internal fun rememberAndroidModelSettingsServices(): ModelSettingsServices {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(context) {
        val stop = RepoIdentity.observeInvalidation { scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { stop() }
    }
    return remember(context, epoch) { androidModelSettingsServices(context) }
}

/** The same source-owned adapter for Android's imperative analysis entry points. */
internal fun androidModelSettingsServices(context: Context): ModelSettingsServices {
    val appContext = context.applicationContext
    val openingEpoch = RepoIdentity.generation
    val repo = Repo(appContext)
    val source = runCatching { repo.source() }
    val memory = EngineModels(appContext)
    ModelCatalogs.warm(appContext)
    return object : ModelSettingsServices {
        override fun current() = openingEpoch == RepoIdentity.generation && source.fold(
            onSuccess = repo::isCurrent,
            onFailure = { runCatching { repo.source() }.isFailure },
        )
        private fun requireCurrent() { if (!current()) throw CancellationException("The model settings source changed.") }
        override fun configuration(): ModelConfiguration {
            requireCurrent()
            return ServerStore.load(appContext).let { ModelConfiguration(it.engine, it.model) }
        }
        override fun save(configuration: ModelConfiguration) {
            requireCurrent()
            ServerStore.save(appContext, ServerStore.load(appContext).copy(engine = configuration.engine, model = configuration.model))
        }
        override fun remembered(engine: String): String? { requireCurrent(); return memory.model(engine) }
        override fun remember(engine: String, model: String) { requireCurrent(); memory.setModel(engine, model) }
        override fun cached(engine: String): CliCatalog? = if (source.isSuccess && current()) ModelCatalogs.get(engine) else null
        override fun signedIn(engine: String): Boolean? = if (current()) knownAiSignIn(repo, engine) else null
        override suspend fun checkSignIns() { if (current()) checkAiSignIns(repo) }
        override suspend fun fetch(engine: String, refresh: Boolean): Result<CliCatalog> = try {
            requireCurrent()
            source.getOrThrow()
            val raw = repo.models(engine, refresh)
            requireCurrent()
            ModelCatalogs.keep(engine, raw)
            Result.success(raw.asCatalog())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            requireCurrent()
            Result.failure(failure)
        }
    }
}
