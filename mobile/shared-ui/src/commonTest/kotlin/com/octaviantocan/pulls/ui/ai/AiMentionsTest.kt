package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AiMentionsTest {
    @Test
    fun namesAndRootRelativeMentionsUseTheCurrentFilesAndForgetTheRetiredSource() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        assertEquals("src/file.kt", codeNameOf(h.page, "file"))
        assertEquals("src/file.kt", resolveMention(h.page, "../src/file.kt"))
        assertEquals("src/file.kt", resolveMention(h.page, "./file.kt"))
        assertNull(resolveMention(h.page, "https://unrelated.example/file.kt"))
        h.handler = { name, args -> if (name == "github-pr")
            Json.parseToJsonElement("""{"key":"pull-1","diff":{"head":{"sha":"head-b"},"files":[{"path":"src/Other.kt","sha":"blob-b","status":"modified"}]},"groups":[]}""")
            else h.default(name, args)
        }
        h.page.refresh(); runCurrent()
        assertNull(codeNameOf(h.page, "file"))
        assertNull(resolveMention(h.page, "file.kt"))
        assertEquals("src/Other.kt", codeNameOf(h.page, "Other"))
        h.generation++
        h.cache.clear()
        assertNull(codeNameOf(h.page, "Other"))
        assertNull(resolveMention(h.page, "Other.kt"))
    }
}
