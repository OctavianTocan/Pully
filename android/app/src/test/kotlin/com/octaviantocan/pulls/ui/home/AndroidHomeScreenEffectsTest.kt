package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.PullSizes
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AndroidHomeScreenEffectsTest {
    @Test
    fun identityInvalidationRevokesTheRealListWriterBeforeTheNewOverviewReadsCounts() {
        PullSizes.clear()
        try {
            val first = PullSizes.source("opaque-first-source", bounded = false)
            val oldList = AndroidHomeScreenEffects(first) { true }
            oldList.size("owner", "repo", 42, 10, 2, 1)
            assertEquals(PullSizes.Size(10, 2, 1), first.get("owner", "repo", 42))

            // PullsApplication does this synchronously, before its main-thread owner cleanup.
            PullSizes.clear()
            oldList.size("owner", "repo", 42, 999, 999, 999)
            assertNull(first.get("owner", "repo", 42))

            val current = PullSizes.source("opaque-new-source", bounded = false)
            assertNull(current.get("owner", "repo", 42))
            val list = AndroidHomeScreenEffects(current) { true }
            list.size("owner", "repo", 42, 30, 4, 2)
            // Even an old callback whose current-source check has not observed invalidation is rejected.
            oldList.size("owner", "repo", 42, 999, 999, 999)
            first.invalidate()
            assertEquals(PullSizes.Size(30, 4, 2), current.get("owner", "repo", 42))
            list.size("owner", "repo", 42, null, null, null)
            assertNull(current.get("owner", "repo", 42))
        } finally {
            PullSizes.clear()
        }
    }
}
