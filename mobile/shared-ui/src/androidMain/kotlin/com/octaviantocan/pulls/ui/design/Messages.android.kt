package com.octaviantocan.pulls.ui.design

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private val messageCatalogs = mutableMapOf<AssetManager, MessageCatalog>()

/** Reads the existing Android locale assets without retaining an activity. */
fun loadAndroidMessages(context: Context, locale: String): Messages =
    messageCatalogs.getOrPut(context.assets) {
        val assets = context.assets
        MessageCatalog { code -> assets.open("i18n/$code.json").bufferedReader().use { it.readText() } }
    }.load(locale)

internal actual fun deviceLocale(): DeviceLocale {
    val locale = Locale.getDefault()
    return DeviceLocale(locale.toLanguageTag(), locale.language, locale.country, locale.script)
}

@Composable
actual fun rememberMessages(locale: String): Messages {
    val context = LocalContext.current
    return remember(context, locale) { loadAndroidMessages(context, locale) }
}
