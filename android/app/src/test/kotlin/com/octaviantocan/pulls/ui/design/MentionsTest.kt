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

    private val changed = listOf("src/cache/key.ts", "src/parser.ts", "README.md")
    private val resolve = { name: String -> changed.firstOrNull { it == name || it.endsWith("/$name") } }

    @Test
    fun backtickedCodeIsAPlaceWhenItNamesAChangedFileOrAPathAndLine() {
        assertEquals(CodePlace("src/cache/key.ts", 0), codeSpanPlaceOf("key.ts", resolve))
        assertEquals(CodePlace("src/cache/key.ts", 42), codeSpanPlaceOf("src/cache/key.ts:42", resolve))
        assertEquals(CodePlace("src/parser.ts", 9), codeSpanPlaceOf("parser.ts#L9", resolve))
        assertEquals(CodePlace("README.md", 0), codeSpanPlaceOf("README.md", resolve))
        // A path the change does not touch is still a place when it has a folder and a line: it opens on GitHub.
        assertEquals(CodePlace("src/other.ts", 3), codeSpanPlaceOf("src/other.ts:3", resolve))
        assertNull(codeSpanPlaceOf("other.ts", resolve))
        assertNull(codeSpanPlaceOf("src/other.ts", resolve))
        assertNull(codeSpanPlaceOf("cacheKey", resolve))
        assertNull(codeSpanPlaceOf("localhost:8080", resolve))
        assertNull(codeSpanPlaceOf("foo.bar()", resolve))
        assertNull(codeSpanPlaceOf("a + b", resolve))
    }

    @Test
    fun aGithubFileLinkIsAPlaceOnlyForAChangedFile() {
        assertEquals(CodePlace("src/cache/key.ts", 42), githubPlaceOf("https://github.com/o/r/blob/abc123/src/cache/key.ts#L42"))
        assertEquals(CodePlace("pages/[id].vue", 0), githubPlaceOf("https://github.com/o/r/blob/abc123/pages/%5Bid%5D.vue"))
        assertNull(githubPlaceOf("https://github.com/o/r/pull/12"))
        assertEquals(CodePlace("src/parser.ts", 3), linkPlaceOf("https://github.com/o/r/blob/abc/src/parser.ts#L3-L9", resolve))
        assertNull(linkPlaceOf("https://github.com/o/r/blob/abc/src/other.ts#L3", resolve))
        // A repository path is a place whether or not the change touches it.
        assertEquals(CodePlace("src/other.ts", 0), linkPlaceOf("src/other.ts", resolve))
    }

    @Test
    fun aGithubLinkIntoAnotherRepositoryStaysAWebLink() {
        assertEquals(CodePlace("src/parser.ts", 3), linkPlaceOf("https://github.com/O/R/blob/abc/src/parser.ts#L3", resolve, "o/r"))
        assertNull(linkPlaceOf("https://github.com/bob/lib/blob/abc/src/parser.ts#L3", resolve, "o/r"))
        assertEquals(listOf<CodePlace>(), mentionedPlaces("[parser](https://github.com/bob/lib/blob/abc/src/parser.ts#L3)", resolve, "o/r"))
    }

    @Test
    fun aChangedFileNamedInTheTextIsAPlaceWithOrWithoutALine() {
        val markdown = "Moves `key.ts` work into parser.ts, see [key.ts:42] and README.md. Version 1.2 and e.g. are not files; nor is other.ts:4."
        assertEquals(listOf(CodePlace("src/cache/key.ts", 0), CodePlace("src/parser.ts", 0), CodePlace("src/cache/key.ts", 42), CodePlace("README.md", 0)), mentionedPlaces(markdown, resolve))
        assertEquals(listOf("parser.ts" to 0, "key.ts" to 42, "README.md" to 0, "other.ts" to 4), bareCites("into parser.ts, see [key.ts:42] and README.md. other.ts:4.").map { it.second to it.third }.toList())
    }
}
