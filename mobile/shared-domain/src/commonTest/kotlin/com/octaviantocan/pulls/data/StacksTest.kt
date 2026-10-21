package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StacksTest {
    private fun entry(number: Int, position: Int, head: String, base: String, current: Boolean = false, extra: String = "") =
        """{"number":$number,"title":"PR $number","state":"open","position":$position,"current":$current,"headRef":"$head","baseRef":"$base"$extra}"""

    @Test
    fun readsTheLayersBottomFirstAndFindsTheOneBelow() {
        val stack = Json.parseToJsonElement(
            """{"native":false,"base":"main","entries":[${entry(243, 3, "c", "b")},${entry(241, 1, "a", "main", extra = ""","checks":"failure","additions":12,"deletions":3""")},${entry(242, 2, "b", "a", current = true)}]}""",
        ).asPrStack()!!
        assertEquals(listOf(241, 242, 243), stack.entries.map { it.number })
        assertEquals(242, stack.current?.number)
        assertEquals(241, stack.below?.number)
        assertEquals("failure", stack.entries.first().checks)
        assertEquals(12, stack.entries.first().additions)
        assertEquals("main", stack.base)
    }

    @Test
    fun theBottomLayerSitsOnNothing() {
        val stack = Json.parseToJsonElement("""{"native":true,"number":4,"base":"main","entries":[${entry(1, 1, "a", "main", current = true)},${entry(2, 2, "b", "a")}]}""").asPrStack()!!
        assertEquals(4, stack.number)
        assertNull(stack.below)
    }

    @Test
    fun noStackOrOneOfOneIsNone() {
        assertNull(Json.parseToJsonElement("null").asPrStack())
        assertNull(Json.parseToJsonElement("""{"native":false,"base":"main","entries":[${entry(1, 1, "a", "main", current = true)}]}""").asPrStack())
    }
}
