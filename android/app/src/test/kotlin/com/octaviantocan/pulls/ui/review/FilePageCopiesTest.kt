package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.PullPage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilePageCopiesTest {
    private val dir: File = Files.createTempDirectory("pages").toFile()

    private fun store(maxPages: Int = 60, current: () -> Boolean = { true }) =
        FilePageCopies(dir, maxPages = maxPages, writer = { it.run() }, current = current)

    private fun page(version: String) = PullPage(buildJsonObject {
        put("version", version)
        put("opened", buildJsonObject { put("key", "github:o/r#1") })
    })

    @Test
    fun aPageComesBackWholeAndItsVersionSurvivesTheApp() {
        store().write("o/r#1", page("a@1"))
        val again = store()
        assertEquals("a@1", again.version("o/r#1"))
        assertEquals(JsonPrimitive("github:o/r#1"), (again.read("o/r#1")?.json?.get("opened") as kotlinx.serialization.json.JsonObject)["key"])
        assertNull(again.read("o/r#2"))
    }

    @Test
    fun theLeastRecentlyUsedPagesAreDroppedFirst() {
        val pages = store(maxPages = 2)
        pages.write("o/r#1", page("a@1"))
        File(dir.listFiles()!!.first { it.name != "index.json" }.path).setLastModified(1_000)
        pages.write("o/r#2", page("a@1"))
        pages.write("o/r#3", page("a@1"))
        assertNull(pages.version("o/r#1"))
        assertNull(pages.read("o/r#1"))
        assertEquals("a@1", store().version("o/r#3"))
    }

    @Test
    fun aStoreForAnAccountThatIsGoneNeitherReadsNorWrites() {
        var current = true
        val pages = store(current = { current })
        pages.write("o/r#1", page("a@1"))
        current = false
        assertNull(pages.read("o/r#1"))
        assertNull(pages.version("o/r#1"))
        pages.write("o/r#2", page("a@1"))
        current = true
        assertNull(pages.read("o/r#2"))
    }
}
