package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PostedRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.asPostedRow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The account's posted diffs, shown from its private copy at once and refreshed quietly. */
class PostedDiffsStore(
    private val repo: Repo,
    private val storage: KeyValueStore,
    private val eligibleAccount: () -> Boolean,
    private val currentSource: () -> Boolean,
) {
    private var generation = 0
    private var invalidated = false
    private fun active() = !invalidated && storage.current && currentSource()
    var rows by mutableStateOf(if (active() && eligibleAccount()) read() else emptyList())
        private set

    private fun read(): List<PostedRow> = storage.get("rows")?.let { text ->
        runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.asPostedRow() } }.getOrNull()
    }.orEmpty()

    /** Signed out there are none; a transient failure keeps the same account's last list. */
    suspend fun refresh() {
        if (!active()) return
        val current = ++generation
        if (!eligibleAccount()) { keep(emptyList()); return }
        try {
            val next = repo.postedDiffs()
            if (active() && current == generation && eligibleAccount()) keep(next)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun delete(row: PostedRow) {
        if (!active() || !eligibleAccount()) return
        val current = ++generation
        repo.deletePosted(row.id)
        if (active() && current == generation && eligibleAccount()) keep(rows.filter { it.id != row.id })
    }

    private fun keep(next: List<PostedRow>) {
        if (!active()) return
        rows = next
        val json = JsonArray(next.map { row ->
            buildJsonObject {
                put("id", row.id); put("title", row.title); put("author", row.author); put("version", row.version)
                put("updatedAt", row.updatedAt); put("files", row.files); put("additions", row.additions); put("deletions", row.deletions)
            }
        })
        storage.set("rows", json.toString())
    }

    fun invalidate() { invalidated = true; generation++; rows = emptyList() }
}
