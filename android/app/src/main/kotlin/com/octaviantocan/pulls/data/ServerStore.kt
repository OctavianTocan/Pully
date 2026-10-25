package com.octaviantocan.pulls.data

import android.content.Context
import com.octaviantocan.pulls.BuildConfig

data class ServerConfig(
    /** The stable app's server, or the staging server for the staging app (android-staging-channel). */
    val url: String = BuildConfig.DEFAULT_SERVER,
    val accessId: String = "",
    val accessSecret: String = "",
    val engine: String = "claude-code",
    val model: String = "",
    /** A Cloudflare Access sign-in made inside Pully ([com.octaviantocan.pulls.ui.settings.AccessSignInSheet]); a service token wins over it. */
    val accessToken: String = "",
) {
    fun headers(): Map<String, String> = buildMap {
        if (accessId.isNotBlank() && accessSecret.isNotBlank()) {
            put("CF-Access-Client-Id", accessId)
            put("CF-Access-Client-Secret", accessSecret)
        } else if (accessToken.isNotBlank()) {
            put(ACCESS_TOKEN_HEADER, accessToken)
        }
    }
}

object ServerStore {
    private const val PREFS = "pulls-server"

    fun bakedHeaders(server: String, path: String): Map<String, String> = stagingAccessHeaders(
        server, path, BuildConfig.DEFAULT_SERVER, BuildConfig.STAGING_ACCESS_CLIENT_ID, BuildConfig.STAGING_ACCESS_CLIENT_SECRET,
    )

    fun updatesReady(context: Context): Boolean = updateConfig(context) != null

    /** Update transport is available at Application/worker startup, before any GitHub workspace is mounted. */
    fun updateConfig(context: Context): ServerConfig? = com.octaviantocan.pulls.update.resolveUpdateConfig(
        NativeEntry.privateConnection(context), { loadSaved(context) }, BuildConfig.DEFAULT_SERVER, BuildConfig.UPDATE_CHANNEL, ::bakedHeaders,
    )

    fun load(context: Context): ServerConfig {
        NativeWorkspaceBinding.active?.let { binding ->
            val prefs = context.getSharedPreferences("pulls-workspace-model-${binding.namespace}", Context.MODE_PRIVATE)
            return ServerConfig(url = BuildConfig.DEFAULT_SERVER,
                engine = prefs.getString("engine", "claude-code") ?: "claude-code", model = prefs.getString("model", "") ?: "")
        }
        return loadSaved(context)
    }

    private fun loadSaved(context: Context): ServerConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ServerConfig(
            url = prefs.getString("url", null) ?: ServerConfig().url,
            accessId = prefs.getString("accessId", "") ?: "",
            accessSecret = prefs.getString("accessSecret", "") ?: "",
            engine = prefs.getString("engine", null) ?: "claude-code",
            model = prefs.getString("model", "") ?: "",
            accessToken = prefs.getString("accessToken", "") ?: "",
        )
    }

    /**
     * Keeps a Cloudflare Access sign-in for the saved server. It names the same person each time, so cached lists and
     * the account stay as they were; only the token is new.
     */
    fun saveAccessToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("accessToken", token.trim()).apply()
        com.octaviantocan.pulls.update.UpdateChecks.sync(context)
    }

    fun save(context: Context, config: ServerConfig) {
        NativeWorkspaceBinding.active?.let { binding ->
            if (!binding.workspace.current) throw kotlinx.coroutines.CancellationException("The GitHub workspace changed.")
            if (config.url != BuildConfig.DEFAULT_SERVER || config.accessId.isNotBlank() || config.accessSecret.isNotBlank()) {
                binding.close()
                save(context, config)
                NativeEntry.select(context, com.octaviantocan.pulls.ui.explore.NativeEntryMode.ExistingServer)
                return
            }
            context.getSharedPreferences("pulls-workspace-model-${binding.namespace}", Context.MODE_PRIVATE).edit()
                .putString("engine", config.engine.trim().ifBlank { "claude-code" }).putString("model", config.model.trim()).apply()
            return
        }
        val previous = load(context)
        val sourceChanged = previous.url.trim().trimEnd('/') != config.url.trim().trimEnd('/') ||
            previous.accessId != config.accessId.trim() || previous.accessSecret != config.accessSecret.trim()
        if (sourceChanged) {
            RepoIdentity.invalidate()
            com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        }
        val urlChanged = previous.url.trim().trimEnd('/') != config.url.trim().trimEnd('/')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // A sign-in belongs to the server it was made on.
            .apply { if (urlChanged) remove("accessToken") }
            .putString("url", config.url.trim())
            .putString("accessId", config.accessId.trim())
            .putString("accessSecret", config.accessSecret.trim())
            .putString("engine", config.engine.trim().ifBlank { "claude-code" })
            .putString("model", config.model.trim())
            .apply()
        if (sourceChanged) Account.connectionChanged(context)
        if (sourceChanged) com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
        if (sourceChanged) com.octaviantocan.pulls.update.UpdateChecks.sync(context)
    }
}
