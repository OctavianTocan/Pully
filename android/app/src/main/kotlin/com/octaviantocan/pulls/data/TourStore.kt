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
class TourStore(private val dir: File) {
    /** Parsed tours by pull request, so a tour opened twice in a run is read from disk once. */
    private val parsed = HashMap<String, Tour>()

    /** The tour kept for [pull], or null when there is none or it cannot be read. */
    @Synchronized
    fun get(pull: String): Tour? {
        parsed[pull]?.let { return it }
        val file = fileOf(pull)
        if (!file.isFile) return null
        return runCatching { (Json.parseToJsonElement(file.readText()) as JsonObject).asTour() }
            .getOrNull()
            ?.takeIf { it.stops.isNotEmpty() }
            ?.also { parsed[pull] = it }
    }

    /** Whether a tour is kept for [pull], without reading it. */
    @Synchronized
    fun has(pull: String): Boolean = parsed.containsKey(pull) || fileOf(pull).isFile

    /**
     * Keeps [json], a `TourResult` as the server sent it, as the tour of [pull]. A tour with no stops is not kept, and the
     * same tour (by when it was written) is not written twice.
     */
    @Synchronized
    fun put(pull: String, json: JsonObject) {
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

    private fun fileOf(pull: String): File = File(dir, "${digest(pull)}.json")

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val LIMIT = 200

        @Volatile private var shared: TourStore? = null

        /** The app's one store, under its private files. */
        fun get(context: Context): TourStore =
            shared ?: synchronized(this) { shared ?: TourStore(File(context.applicationContext.filesDir, "tours")).also { shared = it } }
    }
}
