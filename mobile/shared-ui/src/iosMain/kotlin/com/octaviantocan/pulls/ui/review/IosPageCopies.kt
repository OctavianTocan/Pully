@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.data.PullPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/** The one queue every kept page is written on, so a write, its trim and the index never interleave. */
internal val pageWriter = dispatch_queue_create("com.octaviantocan.pully.page-copies", null)

/**
 * Pull request pages kept as one file each in [directory] (`pages-are-read-before-the-tap`), as Android's
 * `FilePageCopies` keeps them: at most [maxPages] pages and [maxBytes] bytes, the page read or written longest ago
 * dropped first. Pages are written on one background queue, each put in place whole, so a read never sees half a
 * page; [current] turns the store off once the account it was made for is gone.
 */
internal class IosPageCopies(
    private val directory: String,
    private val maxPages: Int = 400,
    private val maxBytes: Long = 200L * 1024 * 1024,
    private val current: () -> Boolean,
) : PageCopies {
    init { require(directory.startsWith("/")) }
    private val manager get() = NSFileManager.defaultManager
    private val guard = NSRecursiveLock()
    /** Each kept page's version, read from the index once. */
    private val versions: MutableMap<String, String> by lazy { readIndex() }

    private fun <T> locked(work: () -> T): T { guard.lock(); return try { work() } finally { guard.unlock() } }

    override fun read(name: String): PullPage? {
        if (!current()) return null
        val path = pathOf(name)
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        val page = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((page["name"] as? JsonPrimitive)?.content != name) return null
        dispatch_async(pageWriter) {
            manager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate()), ofItemAtPath = path, error = null)
        }
        return PullPage(page)
    }

    override fun version(name: String): String? = if (current()) locked { versions[name] } else null

    override fun write(name: String, page: PullPage) {
        if (!current()) return
        locked { page.version?.let { versions[name] = it } ?: versions.remove(name) }
        val text = JsonObject(page.json + ("name" to JsonPrimitive(name))).toString()
        dispatch_async(pageWriter) {
            if (!current()) return@dispatch_async
            runCatching {
                check(manager.createDirectoryAtPath(directory, true, null, null)) { "The page folder could not be made." }
                // Written to a side file and moved in place, so a read never sees half a page.
                check(NSString.create(string = text).writeToFile(pathOf(name), atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
                    "The page could not be put in place."
                }
                trim()
                writeIndex()
            }
        }
    }

    private fun pathOf(name: String) = "$directory/${LinkPlaces.fileAnchor(name)}.json"

    /** Drops the pages read or written longest ago until the rest fit, and forgets their versions. */
    private fun trim() {
        val pages = (manager.contentsOfDirectoryAtPath(directory, error = null) as? List<*>).orEmpty()
            .filterIsInstance<String>().filter { it.endsWith(".json") && it != INDEX }
            .mapNotNull { file ->
                val path = "$directory/$file"
                val attributes = manager.attributesOfItemAtPath(path, error = null) ?: return@mapNotNull null
                val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return@mapNotNull null
                val at = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
                Kept(path, bytes, at)
            }.sortedByDescending { it.at }
        var bytes = 0L
        val dropped = pages.filterIndexed { index, page ->
            bytes += page.bytes
            index >= maxPages || bytes > maxBytes
        }.map { it.path }.toSet()
        if (dropped.isEmpty()) return
        locked { versions.keys.toList().forEach { name -> if (pathOf(name) in dropped) versions.remove(name) } }
        dropped.forEach { manager.removeItemAtPath(it, error = null) }
    }

    private fun readIndex(): MutableMap<String, String> {
        val index = mutableMapOf<String, String>()
        val text = NSString.stringWithContentsOfFile("$directory/$INDEX", NSUTF8StringEncoding, null) ?: return index
        runCatching {
            Json.parseToJsonElement(text).jsonObject.forEach { (name, version) ->
                (version as? JsonPrimitive)?.content?.let { if (manager.fileExistsAtPath(pathOf(name))) index[name] = it }
            }
        }
        return index
    }

    private fun writeIndex() {
        val text = locked { JsonObject(versions.mapValues { JsonPrimitive(it.value) }).toString() }
        NSString.create(string = text).writeToFile("$directory/$INDEX", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private class Kept(val path: String, val bytes: Long, val at: Double)

    private companion object {
        const val INDEX = "index.json"
    }
}

/** Where an account's kept pages live: its own folder under Caches, which the system may empty when space runs low. */
internal fun iosPageDirectory(namespace: String): String {
    require(namespace.length == 64 && namespace.all { it in '0'..'9' || it in 'a'..'f' })
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()?.toString()
        ?: (NSHomeDirectory() + "/Library/Caches")
    return "$caches/PullyPages-v1/$namespace"
}
