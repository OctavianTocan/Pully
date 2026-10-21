package com.octaviantocan.pulls.data

import android.content.Context

/** The engines the server runs a model through, in the order the model picker's tabs show them. */
val ENGINES = listOf("claude-code", "codex", "pi")

/** An engine as the app names it. */
fun engineName(engine: String): String = when (engine) {
    "codex" -> "Codex"
    "pi" -> "Pi"
    else -> "Claude Code"
}

data class ServerConfig(
    val url: String = "https://prs.octaviantocan.com",
    val accessId: String = "",
    val accessSecret: String = "",
    val engine: String = "claude-code",
    val model: String = "",
) {
    fun headers(): Map<String, String> = buildMap {
        if (accessId.isNotBlank() && accessSecret.isNotBlank()) {
            put("CF-Access-Client-Id", accessId)
            put("CF-Access-Client-Secret", accessSecret)
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
        )
    }

    fun save(context: Context, config: ServerConfig) {
        val previous = load(context)
        val sourceChanged = previous.url.trim().trimEnd('/') != config.url.trim().trimEnd('/') ||
            previous.accessId != config.accessId.trim() || previous.accessSecret != config.accessSecret.trim()
        if (sourceChanged) {
            RepoIdentity.invalidate()
            com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", config.url.trim())
            .putString("accessId", config.accessId.trim())
            .putString("accessSecret", config.accessSecret.trim())
            .putString("engine", config.engine.trim().ifBlank { "claude-code" })
            .putString("model", config.model.trim())
            .apply()
        if (sourceChanged) com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }
}
