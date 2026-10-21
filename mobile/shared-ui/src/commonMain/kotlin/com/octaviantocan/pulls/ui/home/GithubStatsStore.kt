package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The source's last GitHub year, kept privately and refreshed under the rendered numbers. */
class GithubStatsStore(
    private val repo: Repo,
    private val storage: KeyValueStore,
    private val currentSource: () -> Boolean,
) {
    private var invalidated = false
    private var generation = 0
    private var asked = false
    private fun active() = !invalidated && storage.current && currentSource()
    var last by mutableStateOf(if (active()) storage.get("last")?.let { raw ->
        runCatching { GithubStats.parse(Json.parseToJsonElement(raw)) }.getOrNull()
    } else null)
        private set

    /** False means no calendar could be obtained; an existing calendar remains readable. */
    suspend fun refresh(): Boolean {
        if (!active()) return false
        val current = ++generation
        return try {
            val result = repo.call("github-stats", buildJsonObject { put("fresh", false) })
            val stats = GithubStats.parse(result) ?: return false
            if (!active() || current != generation) return false
            last = stats
            storage.set("last", result.toString())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Once per native source owner, after the app's first screen has settled. */
    suspend fun warm() {
        if (asked || !active()) return
        asked = true
        refresh()
    }

    fun invalidate() { invalidated = true; generation++; last = null; asked = false }
}
