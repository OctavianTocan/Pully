package com.octaviantocan.pulls.ui.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiConnectionModelFidelityTest {
    @Test
    fun savingTheDrawnModelRetainsItsExactIdentifierAndOnlyEmptyInputClearsIt() = runTest {
        for (model in listOf(" model ", "  é-model  ", " " + "m".repeat(254) + " ")) {
            val h = AiConnectionsHarness(backgroundScope)
            try {
                h.snapshot = h.snapshot.withPi(configuredPi(h.account).copy(preferredModel = model))
                val mount = h.open(AiConnectionKind.PiEndpoint)
                runCurrent()
                val drawn = h.actions(mount)
                assertEquals(model, h.state.modelInput)
                assertTrue(drawn.canSavePi)
                drawn.savePi()
                runCurrent()
                assertEquals(model, h.configurations.single().first.preferredModel)
                assertEquals(model, h.state.view(AiConnectionKind.PiEndpoint)?.preferredModel)

                val fresh = h.actions(mount)
                fresh.model("   ")
                assertFalse(fresh.canSavePi)
                fresh.savePi()
                runCurrent()
                assertEquals(1, h.configurations.size)
                assertEquals(model, h.state.view(AiConnectionKind.PiEndpoint)?.preferredModel)

                fresh.model("")
                assertTrue(fresh.canSavePi)
                fresh.savePi()
                runCurrent()
                assertEquals("", h.configurations.last().first.preferredModel)
                assertEquals(null, h.state.view(AiConnectionKind.PiEndpoint)?.preferredModel)
            } finally {
                h.close()
            }
        }
    }
}
