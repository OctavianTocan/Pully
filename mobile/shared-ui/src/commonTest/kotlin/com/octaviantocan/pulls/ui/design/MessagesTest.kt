package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test
    fun missingAndMalformedPrimaryLocalesKeepEnglishAndUnknownKeysVisible() {
        for (primary in listOf(null, "not JSON", "[]", """{"home":{}}""")) {
            val catalog = MessageCatalog { locale ->
                if (locale == "en") """{"home":{"title":"Inbox"}}""" else primary
            }
            val messages = catalog.load("es")
            assertEquals("Inbox", messages.t("home.title"))
            assertEquals("home.future", messages.t("home.future"))
        }
    }

    @Test
    fun localizedTextInterpolatesArgumentsAndKeepsMissingPlaceholders() {
        val catalog = MessageCatalog { locale ->
            when (locale) {
                "es" -> """{"home":{"hello":"Hola {name}, {pending}"}}"""
                "en" -> """{"home":{"hello":"Hello {name}","fallback":"Hello {name}"}}"""
                else -> null
            }
        }
        val messages = catalog.load("es")
        assertEquals("Hola Tavi, {pending}", messages.t("home.hello", "name" to "Tavi"))
        assertEquals("Hello Tavi", messages.t("home.fallback", "name" to "Tavi"))
    }

    @Test
    fun countSelectsExistingTwoAndThreeFormMessages() {
        val messages = MessageCatalog {
            """{"files":"{n} file | {n} files","tasks":"No tasks | One task | {count} tasks"}"""
        }.load("en")
        assertEquals(listOf("0 files", "1 file", "2 files"), (0..2).map { messages.tc("files", it) })
        assertEquals(listOf("No tasks", "One task", "2 tasks"), (0..2).map { messages.tc("tasks", it) })
        assertEquals("{n} file", messages.t("files"))
    }

    @Test
    fun russianZeroSingularFewAndManyFormsRetainTheirExistingRules() {
        val messages = MessageCatalog { """{"files":"none | one | few | many"}""" }.load("ru")
        val counts = listOf(0, 1, 2, 4, 5, 11, 19, 21, 22, 100)
        assertEquals(
            listOf("none", "one", "few", "few", "many", "many", "many", "one", "few", "many"),
            counts.map { messages.tc("files", it) },
        )
    }

    @Test
    fun eachResourceSourceCachesItsOwnPrimaryAndEnglishFiles() {
        val reads = mutableMapOf<String, Int>()
        val catalog = MessageCatalog { locale ->
            reads[locale] = reads.getOrElse(locale) { 0 } + 1
            if (locale == "en") """{"title":"Inbox"}""" else null
        }
        assertEquals("Inbox", catalog.load("es").t("title"))
        assertEquals("Inbox", catalog.load("es").t("title"))
        assertEquals(mapOf("es" to 1, "en" to 1), reads)
        assertEquals("Different", MessageCatalog { """{"title":"Different"}""" }.load("en").t("title"))
    }

    @Test
    fun nativeLocaleFieldsUseTheSameOfferedLocaleMapping() {
        val examples = listOf(
            DeviceLocale("ZH-tw", "zh", "CN") to "zh-TW",
            DeviceLocale("zh-HK", "zh", "HK") to "zh-TW",
            DeviceLocale("zh-MO", "zh", "MO") to "zh-TW",
            DeviceLocale("zh-Hant-US", "zh", "US", "Hant") to "zh-TW",
            DeviceLocale("zh-Hans-CN", "zh", "CN", "Hans") to "zh-CN",
            DeviceLocale("pt-PT", "pt", "PT") to "pt-BR",
            DeviceLocale("es-MX", "es", "MX") to "es",
            DeviceLocale("ja-JP", "ja", "JP") to "ja",
            DeviceLocale("unknown", "xx") to "en",
        )
        for ((locale, expected) in examples) assertEquals(expected, Messages.detect(locale), locale.tag)
    }
}
