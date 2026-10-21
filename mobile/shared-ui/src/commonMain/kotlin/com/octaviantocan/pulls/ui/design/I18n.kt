package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The web app's vue-i18n messages (the JSON files in `packages/app/src/i18n/locales`, copied into
 * assets by the `web` design check, `pnpm run android:design:update`). Same keys, same `{name}` interpolation, same
 * `one | many` plural form, English fallback.
 */
class Messages(private val locale: String, private val primary: JsonObject, private val fallback: JsonObject) {
    fun t(key: String, vararg args: Pair<String, Any?>): String {
        val raw = lookup(primary, key) ?: lookup(fallback, key) ?: return key
        val values = args.toMap()
        val n = (values["n"] as? Number)?.toInt() ?: (values["count"] as? Number)?.toInt()
        return interpolate(pick(raw, n), values)
    }

    /** `{n} file | {n} files` with an explicit count. */
    fun tc(key: String, count: Int, vararg args: Pair<String, Any?>): String = t(key, "n" to count, "count" to count, *args)

    private fun pick(raw: String, n: Int?): String {
        if (!raw.contains(" | ")) return raw
        val forms = raw.split(" | ")
        if (n == null) return forms.first()
        val index = when {
            locale == "ru" -> russian(n, forms.size)
            forms.size == 3 -> when (n) { 0 -> 0; 1 -> 1; else -> 2 }
            else -> if (n == 1) 0 else 1
        }
        return forms[index.coerceIn(0, forms.lastIndex)]
    }

    private fun russian(choice: Int, length: Int): Int {
        if (choice == 0) return 0
        val teen = choice in 11..19
        val endsWithOne = choice % 10 == 1
        if (length < 4) return if (!teen && endsWithOne) 1 else 2
        if (!teen && endsWithOne) return 1
        if (!teen && choice % 10 in 2..4) return 2
        return 3
    }

    private fun interpolate(text: String, values: Map<String, Any?>): String =
        Regex("""\{(\w+)\}""").replace(text) { match -> values[match.groupValues[1]]?.toString() ?: match.value }

    private fun lookup(root: JsonObject, key: String): String? {
        var node: kotlinx.serialization.json.JsonElement = root
        for (part in key.split('.')) node = (node as? JsonObject)?.get(part) ?: return null
        return (node as? JsonPrimitive)?.contentOrNull
    }

    companion object {
        /** Codes and native names, the order of `LOCALES` in `core/src/locales.ts`. */
        val LOCALES = listOf(
            "en" to "English", "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "ja" to "日本語", "ko" to "한국어",
            "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt-BR" to "Português (Brasil)",
            "ru" to "Русский", "it" to "Italiano", "id" to "Bahasa Indonesia",
        )
        /** The device language mapped onto the offered locales, like `detectLocale` in core. */
        fun detect(): String = detect(deviceLocale())

        fun detect(system: DeviceLocale): String {
            LOCALES.firstOrNull { it.first.equals(system.tag, ignoreCase = true) }?.let { return it.first }
            if (system.language == "zh") return if (system.country in setOf("TW", "HK", "MO") || system.script == "Hant") "zh-TW" else "zh-CN"
            if (system.language == "pt") return "pt-BR"
            return LOCALES.firstOrNull { it.first == system.language }?.first ?: "en"
        }
    }
}

val LocalMessages = compositionLocalOf<Messages> { error("No Messages provided") }

/** `t('a.b', 'n' to 3)` inside a composable. */
@Composable
fun t(key: String, vararg args: Pair<String, Any?>): String = LocalMessages.current.t(key, *args)

@Composable
fun tc(key: String, count: Int, vararg args: Pair<String, Any?>): String = LocalMessages.current.tc(key, count, *args)

/** One native resource source and its parsed locale cache, shared by every screen using it. */
class MessageCatalog(private val readText: (String) -> String?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, JsonObject>()

    private fun read(locale: String): JsonObject = cache.getOrPut(locale) {
        runCatching {
            json.parseToJsonElement(readText(locale).orEmpty()) as JsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    fun load(locale: String): Messages = Messages(locale, read(locale), read("en"))
}

/** Device language fields, supplied by the native locale API. */
data class DeviceLocale(val tag: String, val language: String, val country: String = "", val script: String = "")

internal expect fun deviceLocale(): DeviceLocale

/** Loads the host's bundled web locale files and keeps them across recompositions. */
@Composable
expect fun rememberMessages(locale: String = Messages.detect()): Messages
