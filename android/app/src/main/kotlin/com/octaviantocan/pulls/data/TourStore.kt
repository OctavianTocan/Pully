package com.octaviantocan.pulls.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/**
 * Tours this phone has shown, one file per pull request (`owner/repo#number`), so opening a tour again draws it from
 * the phone at once instead of waiting for the diff and the server. The server stays the source: what it answers
 * replaces the copy here. The [LIMIT] most recently written are kept.
 */
class TourStore(
    private val dir: File,
    private val namespace: String = "",
    private val current: () -> Boolean = { true },
) {
    /** Parsed tours by pull request, so a tour opened twice in a run is read from disk once. */
    private val parsed = HashMap<String, Tour>()

    /** The tour kept for [pull], or null when there is none. A file that cannot be read is removed, so it is fetched again. */
    @Synchronized
    fun get(pull: String): Tour? {
        if (!current()) return null
        parsed[pull]?.let { return it }
        val file = fileOf(pull)
        if (!file.isFile) return null
        val tour = runCatching { (Json.parseToJsonElement(file.readText()) as JsonObject).asTour() }.getOrNull()?.takeIf { it.stops.isNotEmpty() }
        if (tour == null) file.delete() else parsed[pull] = tour
        return tour
    }

    /** Whether a readable tour is kept for [pull]. */
    @Synchronized
    fun has(pull: String): Boolean = get(pull) != null

    /**
     * Keeps [json], a `TourResult` as the server sent it, as the tour of [pull]. A tour with no stops is not kept, and the
     * same tour (by when it was written) is not written twice.
     */
    @Synchronized
    fun put(pull: String, json: JsonObject) {
        if (!current()) return
        val tour = runCatching { json.asTour() }.getOrNull() ?: return
        if (tour.stops.isEmpty()) return
        if (parsed[pull]?.generatedAt == tour.generatedAt && fileOf(pull).isFile) return
        parsed[pull] = tour
        runCatching {
            dir.mkdirs()
            val file = fileOf(pull)
            val temp = File(dir, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
            prune()
        }
    }

    private fun prune() {
        val files = dir.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        if (files.size <= LIMIT) return
        files.sortedByDescending { it.lastModified() }.drop(LIMIT).forEach { it.delete() }
    }

    private fun fileOf(pull: String): File = File(dir, "${digest(if (namespace.isEmpty()) pull else "$namespace\u0000$pull")}.json")

    companion object {
        const val LIMIT = 200

        @Volatile private var shared: Pair<Pair<String, Long>, TourStore>? = null

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun namespace(context: Context): String {
            Account.load(context)
            val eligible = Account.serverHasAccount != false && Account.headers(context).isNotEmpty()
            val config = ServerStore.load(context)
            return tourNamespace(config.url, Account.email, eligible, config.headers())
        }

        /** The app's one store, under its private files. */
        fun get(context: Context): TourStore {
            val context = context.applicationContext
            val name = namespace(context)
            val epoch = RepoIdentity.generation
            val identity = name to epoch
            return shared?.takeIf { it.first == identity }?.second ?: synchronized(this) {
                shared?.takeIf { it.first == identity }?.second ?: TourStore(
                    File(context.filesDir, "tours"), namespace = name,
                    current = { name != INVALID_TOUR_NAMESPACE && RepoIdentity.generation == epoch && namespace(context) == name },
                ).also { shared = identity to it }
            }
        }
    }
}

/** A remembered email is an owner only while this server is eligible to receive its current session. */
internal fun tourNamespace(server: String, email: String?, eligibleSession: Boolean, accessHeaders: Map<String, String> = emptyMap()): String =
    sourceStorageNamespace(server, email, eligibleSession, accessHeaders) ?: INVALID_TOUR_NAMESPACE

private const val INVALID_TOUR_NAMESPACE = "invalid-server"
