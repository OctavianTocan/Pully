package com.octaviantocan.pulls.ui.review

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DiffSelectionOwnershipTest {
    @Test
    fun actualMountedRowHandlerReloadsForEqualFileMetadataRefresh() = runTest {
        assertRowHandlersRefreshForAnEqualFileWithoutChangingTheirHead()
    }
}
