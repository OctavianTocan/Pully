package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.DETAILS_INFO

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Group nav helpers: category maps and per-group progress. */
class GroupNavTest {
    @Test
    fun everyCategoryHasAnIconAndAColor() {
        assertEquals(CATEGORY_ICON.keys, CATEGORY_COLOR.keys)
        assertEquals(16, CATEGORY_ICON.size)
        assertEquals("dots-three-circle-duotone", categoryIcon("unknown"))
    }

    @Test
    fun progressCountsChildrenAndTreatsEmptyAsDone() {
        val files = listOf("a", "b", "c").associateWith { com.octaviantocan.pulls.data.ReviewFile(it, "modified", 1, 2, sha = "sha-$it", patchLines = emptyList()) }
        val group = com.octaviantocan.pulls.data.FileGroup("g", "", listOf("a"), children = listOf(com.octaviantocan.pulls.data.FileGroup("s", "", listOf("b", "c"))))
        assertEquals(1f / 3, groupProgress(group, files, setOf("sha-b")))
        assertEquals(3 to 6, groupStats(group, files))
        assertEquals(1f, groupProgress(com.octaviantocan.pulls.data.FileGroup("e", "", emptyList()), files, emptySet()))
    }

    @Suppress("unused")
    private val unusedJson = JsonObject(emptyMap())
}
