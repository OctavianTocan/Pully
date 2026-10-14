package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mentions of the code in AI text (rule 96). */
class MentionsTest {
    @Test
    fun aRepositoryPathIsAPlace() {
        assertEquals(CodePlace("src/cache/key.ts", 42), codePlaceOf("src/cache/key.ts#L42"))
        assertEquals(CodePlace("src/cache/key.ts", 42), codePlaceOf("src/cache/key.ts#L42-L50"))
        assertEquals(CodePlace("src/cache/key.ts", 7), codePlaceOf("src/cache/key.ts:7"))
        assertEquals(CodePlace("key.ts", 0), codePlaceOf("./key.ts"))
        assertEquals(CodePlace("src/a.ts", 0), codePlaceOf("/src/a.ts"))
    }

    @Test
    fun webLinksAndAnchorsAreNotPlaces() {
        assertNull(codePlaceOf("https://example.com/a.ts"))
        assertNull(codePlaceOf("mailto:someone@example.com"))
        assertNull(codePlaceOf("#overview"))
        assertNull(codePlaceOf("//cdn.example.com/x.js"))
        assertNull(codePlaceOf("src/"))
        assertNull(codePlaceOf(""))
    }

    @Test
    fun placesAreCollectedInOrderOnceEachAndNeverFromCode() {
        val changed = listOf("src/cache/key.ts", "src/parser.ts")
        val resolve = { name: String -> changed.firstOrNull { it == name || it.endsWith("/$name") } }
        val markdown = """
            [`cacheKey`](src/cache/key.ts#L42) feeds parser.ts:9, and [key.ts](src/cache/key.ts#L42) again.
            Not a change: other.ts:3. See [the docs](https://example.com).

            ```ts
            parser.ts:100
            ```
        """.trimIndent()
        assertEquals(listOf(CodePlace("src/cache/key.ts", 42), CodePlace("src/parser.ts", 9)), mentionedPlaces(markdown, resolve))
    }
}
