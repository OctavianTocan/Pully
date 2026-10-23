package com.octaviantocan.pulls.data

import android.content.Context

data class ServerConfig(
    val url: String = "https://prs.octaviantocan.com",
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

    fun load(context: Context): ServerConfig {
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
    }

    fun save(context: Context, config: ServerConfig) {
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
    }
}
