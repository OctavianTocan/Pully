package com.octaviantocan.pulls.ui.review

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ReviewRouteOwnershipTest {
    @Test
    fun mountedRouteReopensAfterServerAndAccountChangesWithoutKeepingPrivateChildState() = runTest {
        assertMountedRouteRecovery()
    }
}
