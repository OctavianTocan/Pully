package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages
import platform.Foundation.scriptCode
import platform.posix.memcpy

private val messageCatalog = MessageCatalog(::readLocale)

@OptIn(ExperimentalForeignApi::class)
private fun readLocale(locale: String): String? {
    val path = NSBundle.mainBundle.pathForResource(locale, ofType = "json", inDirectory = "i18n") ?: return null
    val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
    return ByteArray(data.length.toInt()).apply {
        if (isNotEmpty()) usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    }.decodeToString()
}

internal actual fun deviceLocale(): DeviceLocale {
    val tag = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
    val locale = NSLocale(localeIdentifier = tag)
    return DeviceLocale(tag, locale.languageCode.orEmpty(), locale.countryCode.orEmpty(), locale.scriptCode.orEmpty())
}

@Composable
actual fun rememberMessages(locale: String): Messages = remember(locale) { messageCatalog.load(locale) }
