package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.ui.home.NativeSharedLinkHandoff
import com.octaviantocan.pulls.ui.home.ShareImportSource
import com.octaviantocan.pulls.ui.home.SharedLinkImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedLinkImportTest {
    @Test fun noReadyForegroundSourceMeansNoNativeReadAndSourceChangesDuringReadCannotDeliver() {
        var source: ShareImportSource? = null
        val messages = mutableListOf<String>()
        var reads = 0
        val native = object : NativeSharedLinkHandoff {
            override fun take(namespace: String, server: String): String? {
                reads++
                source = source!!.copy(epoch = source!!.epoch + 1)
                return "canonical-link"
            }
            override fun revoke() = error("Polling must not revoke same-source persisted state.")
        }
        val owner = SharedLinkImport(native, { source }, messages::add)
        owner.sync()
        assertEquals(0, reads)
        source = ShareImportSource("a".repeat(64), "https://server.example", 1, 1)
        owner.sync()
        assertEquals(1, reads)
        assertTrue(messages.isEmpty())
    }

    @Test fun oldNativeReadCannotSurviveUnobservedSourceAbaAndCloseNeverRevokesTheDurableToken() {
        var source = ShareImportSource("a".repeat(64), "https://server.example", 1, 1)
        val messages = mutableListOf<String>()
        var reads = 0
        lateinit var owner: SharedLinkImport
        val native = object : NativeSharedLinkHandoff {
            override fun take(namespace: String, server: String): String? {
                reads++
                source = source.copy(namespace = "b".repeat(64), epoch = 2)
                source = source.copy(namespace = "a".repeat(64), epoch = 3)
                return "canonical-link"
            }
            override fun revoke() = error("A scene close must preserve process-restorable handoff state.")
        }
        owner = SharedLinkImport(native, { source }, messages::add)
        owner.sync()
        assertTrue(messages.isEmpty())
        owner.close()
        owner.sync()
        assertEquals(1, reads)
    }

    @Test fun currentReadDeliversOnlyThroughTheInstalledInboxCallbackAndReentrantCloseRetiresIt() {
        val source = ShareImportSource("a".repeat(64), "https://server.example", 1, 1)
        val messages = mutableListOf<String>()
        var closeDuringRead = false
        lateinit var owner: SharedLinkImport
        val native = object : NativeSharedLinkHandoff {
            override fun take(namespace: String, server: String): String? {
                assertEquals(source.namespace, namespace)
                assertEquals(source.server, server)
                if (closeDuringRead) owner.close()
                return "canonical-link"
            }
            override fun revoke() = error("Import does not replace its account source.")
        }
        owner = SharedLinkImport(native, { source }, messages::add)
        owner.sync()
        assertEquals(listOf("canonical-link"), messages)
        closeDuringRead = true
        owner.sync()
        assertEquals(listOf("canonical-link"), messages)
    }
}
