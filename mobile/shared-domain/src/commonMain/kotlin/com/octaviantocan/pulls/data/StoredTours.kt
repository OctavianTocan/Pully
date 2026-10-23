package com.octaviantocan.pulls.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Private native files; callers serialize this port and the policy which uses it. */
interface StoredTourFiles {
    fun read(pull: String): String?
    fun exists(pull: String): Boolean
    fun write(pull: String, tour: String)
    fun remove(pull: String)
    fun prune(limit: Int)
}

/** The existing Android TourStore's cache policy, shared with the native iPhone file adapter. */
class StoredTours(private val files: StoredTourFiles, private val current: () -> Boolean) {
    private val parsed = linkedMapOf<String, Tour>()

    fun get(pull: String): Tour? {
        if (!current()) return null
        parsed[pull]?.let { return it }
        val raw = safely { files.read(pull) } ?: return null
        if (!current()) return null
        val tour = safely { (Json.parseToJsonElement(raw) as JsonObject).asTour() }?.takeIf { it.stops.isNotEmpty() }
        if (!current()) return null
        if (tour == null) safely { files.remove(pull) }
        else remember(pull, tour)
        return tour.takeIf { current() }
    }

    fun put(pull: String, json: JsonObject) {
        if (!current()) return
        val tour = safely { json.asTour() }?.takeIf { it.stops.isNotEmpty() } ?: return
        if (parsed[pull]?.generatedAt == tour.generatedAt && safely { files.exists(pull) } == true) return
        if (!current()) return
        remember(pull, tour)
        safely {
            files.write(pull, json.toString())
            if (current()) files.prune(LIMIT)
        }
    }

    /** Revocation also releases parsed private content retained by old native service handles. */
    fun clearMemory() { parsed.clear() }

    private fun remember(pull: String, tour: Tour) {
        parsed.remove(pull)
        parsed[pull] = tour
        while (parsed.size > LIMIT) parsed.remove(parsed.keys.first())
    }

    private inline fun <T> safely(work: () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null // A cache failure must never hide the server's usable result.
    }

    companion object { const val LIMIT = 200 }
}
