package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The native implementation encrypts and atomically replaces this blob outside backup storage. */
interface PersonalSessionBlobStorage {
    fun read(): ByteArray?
    fun write(contents: ByteArray)
}

/** A captured Pully session, never a GitHub credential. Pending records have cleanup authority only. */
class StoredPersonalSession internal constructor(
    val origin: String,
    val token: String,
    val accountId: String,
    val githubId: Long,
    val authority: PersonalAuthority = PersonalAuthority.PublicRead,
) {
    internal fun sameCredential(other: StoredPersonalSession): Boolean = origin == other.origin && token == other.token
    internal fun same(other: StoredPersonalSession): Boolean =
        sameCredential(other) && accountId == other.accountId && githubId == other.githubId && authority == other.authority
    override fun toString(): String = "StoredPersonalSession([redacted])"
}

/** UI-dispatcher confined. Persist the complete next state before publishing any in-memory change. */
class PersonalSessionVault(private val storage: PersonalSessionBlobStorage) : PersonalSessionStaging {
    private data class Contents(val accepted: StoredPersonalSession?, val pending: List<StoredPersonalSession>)
    private var contents: Contents? = null
    private val owned = mutableListOf<StoredPersonalSession>()

    /** A cached vault cannot conceal an unavailable native store before new account work. */
    fun verifyStorage() { readContents() }

    fun accepted(origin: PersonalAuthOrigin): StoredPersonalSession? = load().accepted?.takeIf { it.origin == origin.base }
    fun pending(): List<StoredPersonalSession> = load().pending.filter { record -> owned.none { it.sameCredential(record) } }

    /** In-process confirmation work is excluded from recovery cleanup until its native owner closes. */
    fun openStaging(): PersonalSessionStagingOwner = PersonalSessionStagingOwner()

    inner class PersonalSessionStagingOwner internal constructor() : PersonalSessionStaging {
        private val records = mutableListOf<StoredPersonalSession>()
        private var closed = false

        override fun savePending(session: PersonalSession) {
            check(!closed)
            val record = session.record()
            require(owned.none { it.sameCredential(record) })
            this@PersonalSessionVault.savePending(session)
            owned.add(record)
            records.add(record)
        }

        override fun discardPending(session: PersonalSession) = this@PersonalSessionVault.discardPending(session)

        fun close() {
            if (closed) return
            closed = true
            owned.removeAll { record -> records.any { it.sameCredential(record) } }
            records.clear()
        }
    }

    override fun savePending(session: PersonalSession) {
        val record = session.record()
        val previous = load()
        require(previous.accepted?.sameCredential(record) != true)
        previous.pending.firstOrNull { it.sameCredential(record) }?.let { require(it.same(record)); return }
        require(previous.pending.size < MAX_PENDING)
        commit(previous.copy(pending = previous.pending + record))
    }

    /** Called only after bearer confirmation, with the same immutable identity as the staged claim. */
    fun promote(session: PersonalSession) {
        val record = session.record()
        val previous = load()
        require(previous.pending.any { it.same(record) })
        val pending = previous.pending.filterNot { it.same(record) }.toMutableList()
        previous.accepted?.takeUnless { it.same(record) }?.let { old ->
            if (pending.none { it.same(old) }) pending.add(old)
        }
        require(pending.size <= MAX_PENDING)
        commit(Contents(record, pending))
        owned.removeAll { it.sameCredential(record) }
    }

    /** Local sign-out first retires the accepted record into the durable revocation queue. */
    fun retireAccepted(record: StoredPersonalSession) {
        val previous = load()
        if (previous.accepted?.same(record) != true) return
        val pending = previous.pending.filterNot { it.same(record) } + record
        require(pending.size <= MAX_PENDING)
        commit(Contents(null, pending))
    }

    override fun discardPending(session: PersonalSession) = discardPending(session.record())

    /** The caller must have received a successful sign-out response for this captured session. */
    fun discardPending(record: StoredPersonalSession) {
        val previous = load()
        if (previous.pending.none { it.same(record) }) return
        commit(previous.copy(pending = previous.pending.filterNot { it.same(record) }))
        owned.removeAll { it.sameCredential(record) }
    }

    private fun load(): Contents {
        contents?.let { return it }
        return readContents().also { contents = it }
    }

    private fun readContents(): Contents {
        val bytes = try { storage.read() } catch (_: Exception) { unavailable() }
        return if (bytes == null) Contents(null, emptyList()) else try {
            require(bytes.size <= MAX_BYTES)
            val value = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
            require((value["version"] as? JsonPrimitive)?.longOrNull == 1L)
            val accepted = value["accepted"]?.takeUnless { it == JsonNull }?.record()
            val pending = (value["pending"] as JsonArray).map { it.record() }
            require(pending.size <= MAX_PENDING)
            val all = listOfNotNull(accepted) + pending
            require(all.map { it.origin to it.token }.distinct().size == all.size)
            Contents(accepted, pending)
        } catch (_: Exception) { unavailable() }
        finally { bytes.fill(0) }
    }

    private fun commit(next: Contents) {
        val bytes = buildJsonObject {
            put("version", 1)
            put("accepted", next.accepted?.json() ?: JsonNull)
            put("pending", JsonArray(next.pending.map { it.json() }))
        }.toString().encodeToByteArray()
        try {
            require(bytes.size <= MAX_BYTES)
            storage.write(bytes)
            contents = next
        } catch (_: Exception) { unavailable() }
        finally { bytes.fill(0) }
    }

    private fun PersonalSession.record() = StoredPersonalSession(origin, token, status.accountId, status.githubId, status.authority).validated()
    private fun kotlinx.serialization.json.JsonElement.record(): StoredPersonalSession {
        val value = this as JsonObject
        fun string(name: String) = (value[name] as JsonPrimitive).also { require(it.isString) }.content
        val githubId = (value["githubId"] as JsonPrimitive).also { require(!it.isString) }.longOrNull ?: unavailable()
        val authority = if ("authority" !in value) PersonalAuthority.PublicRead else when (string("authority")) {
            "publicRead" -> PersonalAuthority.PublicRead
            "privateRepositories" -> PersonalAuthority.PrivateRepositories
            else -> unavailable()
        }
        return StoredPersonalSession(string("origin"), string("token"), string("accountId"), githubId, authority).validated()
    }
    private fun StoredPersonalSession.validated(): StoredPersonalSession {
        require(PersonalAuthOrigin.parse(origin).base == origin)
        require(token.startsWith("pully_") && canonicalPersonalSecret(token.removePrefix("pully_")))
        require(accountId.length in 1..128 && accountId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" })
        require(githubId > 0)
        return this
    }
    private fun StoredPersonalSession.json() = buildJsonObject {
        put("origin", origin); put("token", token); put("accountId", accountId); put("githubId", githubId)
        put("authority", if (authority == PersonalAuthority.PublicRead) "publicRead" else "privateRepositories")
    }
    private fun unavailable(): Nothing = throw PersonalAuthError(PersonalAuthProblem.Unavailable)

    companion object {
        const val MAX_PENDING = 16
        const val MAX_BYTES = 64 * 1024
    }
}
