package com.octaviantocan.pulls.data

import android.content.Context

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", config.url.trim())
            .putString("accessId", config.accessId.trim())
            .putString("accessSecret", config.accessSecret.trim())
            .putString("engine", config.engine.trim().ifBlank { "claude-code" })
            .putString("model", config.model.trim())
            .apply()
    }
}
