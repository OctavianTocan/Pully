@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.PullPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.darwin.dispatch_sync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosPageCopiesTest {
    private val dir = NSTemporaryDirectory() + "pages-" + NSUUID().UUIDString

    @AfterTest fun clean() { NSFileManager.defaultManager.removeItemAtPath(dir, error = null) }

    private fun store(maxPages: Int = 400, current: () -> Boolean = { true }) = IosPageCopies(dir, maxPages = maxPages, current = current)

    /** Waits for the writes queued so far. */
    private fun settled() = dispatch_sync(pageWriter) {}

    private fun page(version: String) = PullPage(buildJsonObject {
        put("version", version)
        put("opened", buildJsonObject { put("key", "github:o/r#1") })
    })

    @Test
    fun aPageComesBackWholeAndItsVersionSurvivesTheApp() {
        store().write("o/r#1", page("a@1"))
        settled()
        val again = store()
        assertEquals("a@1", again.version("o/r#1"))
        assertEquals(JsonPrimitive("github:o/r#1"), (again.read("o/r#1")?.json?.get("opened") as JsonObject)["key"])
        assertNull(again.read("o/r#2"))
    }

    @Test
    fun theOldestPagesAreDroppedOnceTheStoreIsFull() {
        val pages = store(maxPages = 2)
        pages.write("o/r#1", page("a@1"))
        settled()
        pages.write("o/r#2", page("a@1"))
        settled()
        pages.write("o/r#3", page("a@1"))
        settled()
        assertEquals("a@1", store().version("o/r#3"))
        assertEquals(2, listOf("o/r#1", "o/r#2", "o/r#3").count { pages.read(it) != null })
    }

    @Test
    fun aStoreForAnAccountThatIsGoneNeitherReadsNorWrites() {
        var current = true
        val pages = store(current = { current })
        pages.write("o/r#1", page("a@1"))
        settled()
        current = false
        assertNull(pages.read("o/r#1"))
        assertNull(pages.version("o/r#1"))
        pages.write("o/r#2", page("a@1"))
        settled()
        current = true
        assertNull(pages.read("o/r#2"))
    }
}
