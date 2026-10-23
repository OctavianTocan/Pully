package com.octaviantocan.pulls.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.settings.SharedPreferencesSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.AnalyticsSummary
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.sourceStorageNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Original Android store/API; an opaque marker prevents a legacy URL key crossing account/Access owners. */
internal object StatsKeep {
    private const val PREFS = "pulls-stats"
    private var warmed = false

    internal fun services(context: Context, repo: Repo): StatsServices {
        val app = context.applicationContext
        val settings = AppSettings.get(app)
        val epoch = RepoIdentity.generation
        val config = ServerStore.load(app)
        Account.load(app)
        val namespace = sourceStorageNamespace(config.url, Account.email,
            Account.serverHasAccount != false && Account.headers(app).isNotEmpty(), config.headers())
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SourceStatsServices(
            repo, AndroidStatsPreferences(prefs, config.url, namespace),
            { settings.usageStats },
            { epoch == RepoIdentity.generation },
        )
    }

    fun get(context: Context, days: Int): AnalyticsSummary? = services(context, Repo(context)).cached(days)
    suspend fun fetch(context: Context, repo: Repo, days: Int): AnalyticsSummary = services(context, repo).fetch(days)

    /** The existing once-per-launch warm facade, with the same local Usage stats gate. */
    suspend fun warm(context: Context) {
        if (warmed || !AppSettings.get(context).usageStats) return
        warmed = true
        try { fetch(context, Repo(context), 7) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* An empty page can retry; a kept summary still opens immediately. */ }
    }
}

@Composable
internal fun rememberAndroidStatsServices(): StatsServices {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(context) {
        val remove = RepoIdentity.observeInvalidation { scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { remove() }
    }
    return remember(context, epoch) { StatsKeep.services(context, Repo(context)) }
}

/** Only the storage-key adapter differs; parsing, in-memory cache, fetching and ownership stay shared. */
private class AndroidStatsPreferences(
    private val preferences: SharedPreferences,
    private val server: String,
    private val namespace: String?,
) : SettingsPreferences by SharedPreferencesSettings(preferences) {
    private fun key(name: String): String = "$server days:${name.removePrefix("days:")}"
    override fun getString(name: String, default: String?): String? {
        val key = key(name)
        // The old JSON key is retained, but unmarked data is never assigned to a new private owner.
        if (namespace == null || preferences.getString("owner:$key", null) != namespace) return default
        return preferences.getString(key, default)
    }
    override fun edit(): SettingsPreferences.Editor {
        val editor = preferences.edit()
        val delegate = SharedPreferencesSettings(preferences).edit()
        return object : SettingsPreferences.Editor by delegate {
            override fun putString(name: String, value: String?): SettingsPreferences.Editor {
                val key = key(name)
                editor.putString(key, value).putString("owner:$key", namespace)
                return this
            }
            override fun apply() { editor.apply() }
        }
    }
}
