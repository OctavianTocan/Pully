package com.octaviantocan.pulls.ui.book

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the asset files the UI Book ships, the way the app does, so a bad shape fails here and not on a phone. */
class BookDataTest {
    private val book = BookData.parse { File("src/main/assets/book/$it").readText() }

    @Test
    fun pagesPointAtComponentsThatExist() {
        assertTrue(book.pages.isNotEmpty())
        book.pages.mapNotNull { it.component }.forEach { assertTrue(book.components.containsKey(it), "no component $it") }
    }

    @Test
    fun everyComponentHasOneDefaultVariantAndItsSource() {
        assertTrue(book.components.isNotEmpty())
        book.components.values.forEach { component ->
            assertEquals(1, component.variants.count { it.isDefault }, "default variants of ${component.id}")
            assertTrue(book.declarations[component.id].orEmpty().isNotEmpty(), "no source for ${component.id}")
        }
    }

    @Test
    fun everyComponentHasPreviewsForItsVariants() {
        book.components.values.forEach { component ->
            assertEquals(component.variants.map { it.id }, bookPreviews(component.id).map { it.id }, "previews of ${component.id}")
        }
    }

    @Test
    fun rulesRunFromOneWithoutGaps() {
        assertEquals((1..book.rules.size).toList(), book.rules.map { it.n })
        assertTrue(book.rules.all { it.checkedBy.isNotEmpty() })
    }

    @Test
    fun tokensAreReadWithTheirDarkPair() {
        assertTrue(book.colors.isNotEmpty() && book.colors.all { it.dark.isNotEmpty() })
        assertTrue(book.typography.isNotEmpty() && book.rounded.isNotEmpty() && book.spacing.isNotEmpty() && book.elevation.isNotEmpty())
        book.colors.forEach { hexColor(it.light) }
    }

    @Test
    fun theCopiedPromptNamesTheComposable() {
        val page = book.pages.first { it.component != null }
        val component = book.components.getValue(page.component!!)
        val prompt = promptFor(page, component, book.declarations[component.id].orEmpty())
        assertTrue(prompt.contains(component.composable))
        assertTrue(prompt.contains("Do not restyle it."))
    }
}
