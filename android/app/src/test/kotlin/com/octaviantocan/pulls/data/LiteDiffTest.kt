package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.ui.review.patchesAround
import kotlin.test.Test
import kotlin.test.assertEquals

/** The screen's bounded lazy-patch request window, using the shared file model. */
class LiteDiffTest {
    @Test
    fun aFileOnScreenAsksForItselfAndTheNextLeftOutOnes() {
        val many = (1..10).map { ReviewFile("f$it.ts", "modified", 1, 1, patchLines = emptyList(), patchOmitted = it % 2 == 0) }
        assertEquals(listOf("f4.ts", "f6.ts", "f8.ts", "f10.ts"), patchesAround(many, "f4.ts"))
        // The file asked for comes first even when its own patch is there, and at most six go in one request.
        assertEquals(listOf("f1.ts", "f2.ts", "f4.ts", "f6.ts", "f8.ts", "f10.ts"), patchesAround(many, "f1.ts"))
        assertEquals(listOf("f9.ts", "f10.ts"), patchesAround(many, "f9.ts"))
    }

}
