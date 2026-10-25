package com.octaviantocan.pulls.data.personal

import com.octaviantocan.pulls.data.*
import kotlin.test.*

class LocalAccountSettingsTest {
    private val origin = PersonalAuthOrigin.parse("https://accounts.example")

    @Test fun coldLocalPreferenceSelectionRequiresAnAcceptedPrivateAccountAndClearsOnSignOut() {
        assertNull(savedAccountSettingsNamespace(origin, PersonalSessionVault(Blob())))
        val pending = PersonalSessionVault(Blob(contents("privateRepositories", accepted = false)))
        assertNull(savedAccountSettingsNamespace(origin, pending), "Pending credentials have no local account preference owner")
        val vault = PersonalSessionVault(Blob(contents("privateRepositories")))
        assertEquals(sourceStorageNamespace("${origin.base}/workspace", "fixture-account", true), savedAccountSettingsNamespace(origin, vault))
        assertNull(savedAccountSettingsNamespace(PersonalAuthOrigin.parse("https://other.example"), vault))
        vault.retireAccepted(vault.accepted(origin)!!)
        assertNull(savedAccountSettingsNamespace(origin, vault))
    }

    @Test fun publicOnlyAndUnavailableCachedStorageNeverSelectPrivateOrLegacyPreferences() {
        val publicVault = PersonalSessionVault(Blob(contents("publicRead")))
        assertNull(savedAccountSettingsNamespace(origin, publicVault))
        val blob = Blob(contents("privateRepositories"))
        val privateVault = PersonalSessionVault(blob)
        assertNotNull(savedAccountSettingsNamespace(origin, privateVault))
        blob.unavailable = true
        assertNull(savedAccountSettingsNamespace(origin, privateVault), "A cached accepted record must not conceal a failed native storage probe")
    }

    private fun contents(authority: String, accepted: Boolean = true): ByteArray {
        val record = """{"origin":"${origin.base}","token":"pully_${"A".repeat(43)}","accountId":"fixture-account","githubId":42,"authority":"$authority"}"""
        return """{"version":1,"accepted":${if (accepted) record else "null"},"pending":[${if (accepted) "" else record}]}""".encodeToByteArray()
    }

    private class Blob(initial: ByteArray? = null) : PersonalSessionBlobStorage {
        var unavailable = false
        private var bytes: ByteArray? = initial?.copyOf()
        override fun read(): ByteArray? { check(!unavailable); return bytes?.copyOf() }
        override fun write(contents: ByteArray) { bytes = contents.copyOf() }
    }
}
