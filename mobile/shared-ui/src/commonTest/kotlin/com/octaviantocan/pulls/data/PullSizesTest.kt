package com.octaviantocan.pulls.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullSizesTest {
    @AfterTest fun reset() { PullSizes.clear() }

    @Test fun currentListFactsReplaceCountsAndUnknownSizeForgetsOldRevision() {
        PullSizes.put("Owner", "Repo", 1, 2, 3, 4)
        assertEquals(PullSizes.Size(2, 3, 4), PullSizes.get("owner", "repo", 1))
        PullSizes.put("owner", "repo", 1, null, 3, 4)
        assertNull(PullSizes.get("owner", "repo", 1))
    }

    @Test fun changingSourceClearsCountsAndLateOldOwnerCannotRefillThem() {
        val before = PullSizes.source("opaque-source-a")
        before.put("o", "r", 1, 1, 1, 1)
        val current = PullSizes.source("opaque-source-b")
        assertNull(PullSizes.get("o", "r", 1))
        before.put("o", "r", 1, 8, 8, 8)
        before.invalidate()
        current.put("o", "r", 2, 2, 2, 2)
        assertNull(before.get("o", "r", 2))
        assertEquals(PullSizes.Size(2, 2, 2), current.get("o", "r", 2))
        current.invalidate()
        current.put("o", "r", 3, 3, 3, 3)
        assertNull(PullSizes.get("o", "r", 3))
    }

    @Test fun androidSourceLeaseRetainsLargeListCountsAndStillRejectsPriorSource() {
        val android = PullSizes.source("android-source-a", bounded = false)
        repeat(PullSizes.MAX_SIZES + 100) { android.put("o", "r", it, 1, 2, 3) }
        assertEquals(PullSizes.Size(1, 2, 3), android.get("o", "r", 0))
        val replacement = PullSizes.source("android-source-b", bounded = false)
        assertNull(android.get("o", "r", 0))
        android.put("o", "r", 5, 8, 8, 8)
        assertNull(replacement.get("o", "r", 5))
    }

    @Test fun repeatedCurrentSourceKeepsFactsAndCacheEvictsOldestUpdatedEntry() {
        val source = PullSizes.source("opaque-source")
        repeat(PullSizes.MAX_SIZES) { source.put("o", "r", it, 1, 1, 1) }
        PullSizes.source("opaque-source").put("o", "r", 0, 2, 2, 2)
        source.put("o", "r", PullSizes.MAX_SIZES, 1, 1, 1)
        assertNull(source.get("o", "r", 1))
        assertEquals(PullSizes.Size(2, 2, 2), source.get("o", "r", 0))
    }
}
