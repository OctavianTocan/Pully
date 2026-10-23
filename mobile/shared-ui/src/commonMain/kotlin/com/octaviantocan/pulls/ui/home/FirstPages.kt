package com.octaviantocan.pulls.ui.home

import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native private disk/defaults I/O; the answer keeps its existing repo-pulls JSON shape. */
interface RepoPageStorage {
    suspend fun read(owner: String, name: String): JsonObject?
    suspend fun write(owner: String, name: String, raw: JsonObject)
}

/** The source owner's twelve most recently opened repository first pages, with the device copy underneath. */
class FirstPages(private val storage: RepoPageStorage, private val currentSource: () -> Boolean) {
    class Page(val items: List<RepoPull>, val totalCount: Int?, val next: String?)
    private val pages = LinkedHashMap<String, Page>()
    private var invalidated = false
    private val writes = Mutex()
    private fun active() = !invalidated && currentSource()
    private fun key(owner: String, name: String) = "$owner/$name".lowercase()

    fun get(owner: String, name: String): Page? {
        if (!active()) return null
        val key = key(owner, name)
        return pages.remove(key)?.also { pages[key] = it }
    }

    fun put(owner: String, name: String, page: Page) {
        if (!active()) return
        val key = key(owner, name)
        pages.remove(key); pages[key] = page
        while (pages.size > LIMIT) pages.remove(pages.keys.first())
    }

    suspend fun read(owner: String, name: String): Page? {
        if (!active()) return null
        val raw = try { storage.read(owner, name) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        if (!active()) return null
        return runCatching { raw.asRepoPullsPage().let { Page(it.items, it.totalCount, it.next) } }.getOrNull()
    }

    suspend fun write(owner: String, name: String, raw: JsonObject) = writes.withLock {
        if (!active()) return@withLock
        try { storage.write(owner, name, raw) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    fun invalidate() { invalidated = true; pages.clear() }
    companion object { const val LIMIT = 12 }
}
