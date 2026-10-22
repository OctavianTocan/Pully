@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosAnalyticsDiskTest {
    @Test
    fun theActualFileWorkerHonorsSubmissionOrderAndDefersNestedWork() = runTest {
        val order = mutableListOf<Int>()
        repeat(64) { index -> IosAnalyticsFiles.io.execute { order += index } }
        onFiles { assertEquals((0 until 64).toList(), order) }
        val nested = CompletableDeferred<Unit>()
        IosAnalyticsFiles.io.execute {
            order += 64
            IosAnalyticsFiles.io.execute {
                try {
                    assertEquals((0..66).toList(), order)
                    nested.complete(Unit)
                } catch (failure: Throwable) { nested.completeExceptionally(failure) }
            }
            order += 65
            order += 66
        }
        nested.await()
    }

    @Test
    fun aReplacementReaderRejectsAFormerWritersLateSnapshot() = runTest {
        val namespace = namespace()
        onFiles {
            val former = IosAnalyticsDisk(namespace)
            former.read()
            former.replace(listOf("first"))
            val replacement = IosAnalyticsDisk(namespace)
            assertEquals(listOf("first"), replacement.read())
            replacement.replace(listOf("replacement"))
            former.replace(listOf("late former writer"))
            assertEquals(listOf("replacement"), replacement.read())
            replacement.replace(emptyList())
        }
    }

    @Test
    fun optingOutFencesRetiredWritesBeforeDeletingAndReenablingTheQueue() = runTest {
        val defaults = NSUserDefaults.standardUserDefaults
        val previous = defaults.objectForKey("pulls-ui.usageStats")
        val namespace = namespace()
        try {
            defaults.setBool(true, forKey = "pulls-ui.usageStats")
            val old = IosAnalyticsDisk(namespace)
            onFiles { old.read(); old.replace(listOf("old")) }
            defaults.setBool(false, forKey = "pulls-ui.usageStats")
            IosAnalyticsFiles.invalidateWrites()
            IosAnalyticsFiles.dropAll()
            // The actual serial worker must finish deletion before a later load can run.
            onFiles { old.replace(listOf("late")) }
            defaults.setBool(true, forKey = "pulls-ui.usageStats")
            onFiles {
                val replacement = IosAnalyticsDisk(namespace)
                assertTrue(replacement.read().isEmpty())
                replacement.replace(listOf("new"))
                old.replace(listOf("resurrected"))
                assertEquals(listOf("new"), replacement.read())
                replacement.replace(emptyList())
            }
        } finally {
            if (previous == null) defaults.removeObjectForKey("pulls-ui.usageStats")
            else defaults.setObject(previous, forKey = "pulls-ui.usageStats")
        }
    }

    private suspend fun onFiles(action: () -> Unit) {
        val result = CompletableDeferred<Unit>()
        IosAnalyticsFiles.io.execute {
            try { action(); result.complete(Unit) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        result.await()
    }
    private fun namespace() = NSUUID().UUIDString.replace("-", "").lowercase().padEnd(64, '0')
}
