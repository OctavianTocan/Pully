package com.octaviantocan.pulls.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalSessionVaultTest {
    @Test
    fun restartNeverPromotesAPendingBearerAndPromotionPersistsOnlyItsImmutableIdentity() {
        val storage = MemoryBlob()
        val original = session(1)
        val vault = PersonalSessionVault(storage)
        val owner = vault.openStaging()
        owner.savePending(original)
        assertTrue(vault.pending().isEmpty(), "An active confirmation is not recovery work")
        val restarted = PersonalSessionVault(storage)
        assertNull(restarted.accepted(origin))
        assertEquals(original.token, restarted.pending().single().token)
        vault.promote(original)
        owner.close()
        val accepted = PersonalSessionVault(storage).accepted(origin)!!
        assertEquals(original.status.accountId, accepted.accountId)
        assertEquals(original.status.githubId, accepted.githubId)
        assertFalse(accepted.toString().contains(original.token))
        assertFalse(storage.bytes!!.decodeToString().contains("reader"), "A restored profile needs fresh verification")
    }

    @Test
    fun failedAtomicWritesLeaveBothExistingAccountAndPendingRecoveryIntact() {
        val storage = MemoryBlob()
        val vault = PersonalSessionVault(storage)
        val original = session(1)
        vault.savePending(original); vault.promote(original)
        val replacement = session(2)
        vault.savePending(replacement)
        storage.rejectWrites = true
        assertFailsWith<PersonalAuthError> { vault.promote(replacement) }
        assertEquals(original.token, vault.accepted(origin)!!.token)
        assertEquals(replacement.token, vault.pending().single().token)
        assertEquals(original.token, PersonalSessionVault(storage).accepted(origin)!!.token)
        assertFailsWith<PersonalAuthError> { vault.retireAccepted(vault.accepted(origin)!!) }
        assertEquals(original.token, vault.accepted(origin)!!.token)
    }

    @Test
    fun replacementAndSignOutQueueCapturedCredentialsWithoutDiscardingThem() {
        val storage = MemoryBlob()
        val vault = PersonalSessionVault(storage)
        val original = session(1)
        val replacement = session(2, "https://different.example")
        vault.savePending(original); vault.promote(original)
        vault.savePending(replacement); vault.promote(replacement)
        assertNull(vault.accepted(origin))
        assertEquals(original.token, vault.pending().single().token)
        val accepted = vault.accepted(PersonalAuthOrigin.parse(replacement.origin))!!
        vault.retireAccepted(accepted)
        assertNull(vault.accepted(PersonalAuthOrigin.parse(replacement.origin)))
        assertEquals(setOf(original.token, replacement.token), PersonalSessionVault(storage).pending().map { it.token }.toSet())
        vault.discardPending(accepted)
        assertEquals(original.token, vault.pending().single().token)
    }

    @Test
    fun aStagedCredentialCannotBeRelabelledAsAnotherAccountOrDowngradeAnAcceptedRecord() {
        val vault = PersonalSessionVault(MemoryBlob())
        val original = session(1)
        vault.savePending(original)
        val relabelled = PersonalSession(original.token, original.status.copy(accountId = "different", githubId = 99), original.origin)
        assertFailsWith<IllegalArgumentException> { vault.savePending(relabelled) }
        assertFailsWith<IllegalArgumentException> { vault.promote(relabelled) }
        vault.promote(original)
        assertFailsWith<IllegalArgumentException> { vault.savePending(original) }
        assertEquals(42L, vault.accepted(origin)!!.githubId)
    }

    @Test
    fun recoveryQueueRefusesOverflowAndLosingAnOwnerReleasesOnlyItsOwnStage() {
        val vault = PersonalSessionVault(MemoryBlob())
        val first = vault.openStaging()
        val second = vault.openStaging()
        first.savePending(session(1)); second.savePending(session(2))
        first.close()
        assertEquals(session(1).token, vault.pending().single().token)
        for (i in 3..PersonalSessionVault.MAX_PENDING) vault.savePending(session(i))
        assertFailsWith<IllegalArgumentException> { vault.savePending(session(100)) }
        second.close()
        assertEquals(PersonalSessionVault.MAX_PENDING, vault.pending().size)
        assertFailsWith<IllegalStateException> { first.savePending(session(101)) }
    }

    @Test
    fun unreadableOrCorruptStorageFailsClosedWithNoRawCredentialInItsError() {
        for (content in listOf("not-json", """{"version":2,"accepted":null,"pending":[]}""", """{"version":1,"accepted":null,"pending":[{"token":"private"}]}""")) {
            val storage = MemoryBlob().apply { bytes = content.encodeToByteArray() }
            val failure = assertFailsWith<PersonalAuthError> { PersonalSessionVault(storage).pending() }
            assertEquals(PersonalAuthProblem.Unavailable, failure.problem)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
        val storage = object : PersonalSessionBlobStorage {
            override fun read(): ByteArray? = error("keystore-file=private-user")
            override fun write(contents: ByteArray) = error("unused")
        }
        val failure = assertFailsWith<PersonalAuthError> { PersonalSessionVault(storage).accepted(origin) }
        assertFalse(failure.message.orEmpty().contains("private-user"))
    }

    internal class MemoryBlob : PersonalSessionBlobStorage {
        var bytes: ByteArray? = null
        var rejectWrites = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(contents: ByteArray) {
            check(!rejectWrites) { "fixture storage is unavailable" }
            bytes = contents.copyOf()
        }
    }

    companion object {
        internal val origin = PersonalAuthOrigin.parse("https://accounts.example")
        internal fun session(number: Int, address: String = origin.base): PersonalSession = PersonalSession(
            "pully_" + proofBase64.encode(ByteArray(32) { number.toByte() }),
            PersonalAccountStatus("account-42", 42, "reader", PersonalAuthority.PublicRead, 3600, null, false), address,
        )
    }
}
