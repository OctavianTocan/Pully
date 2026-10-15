package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullStack
import com.octaviantocan.pulls.data.asPull
import com.octaviantocan.pulls.data.asPulls
import com.octaviantocan.pulls.ui.home.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A listed pull request's branches and stack survive the device copy (`toJson`, then `asPull`). */
class PullStackTest {
    private val server = """
        [{"owner":"acme","repo":"app","number":242,"title":"Second layer","author":"octo","isDraft":false,"updatedAt":"2026-10-11T00:00:00Z",
          "labels":[],"state":"open","role":"authored","baseRef":"stack/one","headRef":"stack/two",
          "stack":{"id":"inferred:acme/app:stack/one","position":2,"size":3,"native":false,"below":241}},
         {"owner":"acme","repo":"app","number":241,"title":"First layer","author":"octo","isDraft":false,"updatedAt":"2026-10-10T00:00:00Z",
          "labels":[],"state":"open","role":"authored","baseRef":"main","headRef":"stack/one",
          "stack":{"id":"native:acme/app:7","position":1,"size":3,"native":true}},
         {"owner":"acme","repo":"app","number":9,"title":"Alone","author":"octo","isDraft":false,"updatedAt":"2026-10-09T00:00:00Z",
          "labels":[],"state":"open","role":"owned"}]
    """.trimIndent()

    @Test
    fun readsTheBranchesAndTheStackOfAListedPullRequest() {
        val (second, first, alone) = Json.parseToJsonElement(server).asPulls()
        assertEquals("stack/one", second.baseRef)
        assertEquals("stack/two", second.headRef)
        assertEquals(PullStack("inferred:acme/app:stack/one", position = 2, size = 3, native = false, below = 241), second.stack)
        assertEquals(PullStack("native:acme/app:7", position = 1, size = 3, native = true, below = null), first.stack)
        assertNull(alone.stack)
        assertEquals("", alone.baseRef)
    }

    @Test
    fun theDeviceCopyRoundTrips() {
        val rows = Json.parseToJsonElement(server).asPulls()
        val back = JsonArray(rows.map { it.toJson() }).asPulls()
        assertEquals(rows, back)
        // Through text too, as the device stores it.
        assertEquals(rows, rows.map { Json.parseToJsonElement(it.toJson().toString()).jsonObject.asPull() })
    }

    @Test
    fun aStackWithoutAPlaceIsNoStack() {
        val row = Json.parseToJsonElement("""{"owner":"a","repo":"b","number":1,"title":"t","author":"x","isDraft":false,"updatedAt":"2026-10-11T00:00:00Z","stack":{"id":"inferred:a/b:x"}}""").jsonObject.asPull()
        assertNull(row.stack)
    }
}
