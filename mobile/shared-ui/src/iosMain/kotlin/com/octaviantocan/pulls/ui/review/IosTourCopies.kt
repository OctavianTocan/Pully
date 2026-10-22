@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.data.StoredTourFiles
import com.octaviantocan.pulls.data.StoredTours
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.ui.home.HomeTourCopies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One active writer per private root, including an old scene whose native callback arrives late. */
private object TourFileOwners {
    private val guard = NSRecursiveLock()
    private val worker = dispatch_queue_create("com.octaviantocan.pully.tour-copy-files", null)
    private var next = 0L
    private val leases = mutableMapOf<String, Long>()
    fun <T> locked(work: () -> T): T { guard.lock(); return try { work() } finally { guard.unlock() } }
    fun acquire(root: String): Long = (++next).also { leases[root] = it }
    fun current(root: String, token: Long): Boolean = leases[root] == token
    fun release(root: String, token: Long) { if (current(root, token)) leases.remove(root) }
    suspend fun <T> onFiles(work: () -> T): T = suspendCancellableCoroutine { pending ->
        dispatch_async(worker) {
            if (!pending.isActive) return@dispatch_async
            try {
                val result = locked {
                    if (!pending.isActive) throw CancellationException("The tour copy request ended.")
                    work()
                }
                pending.resume(result)
            } catch (failure: Throwable) { pending.resumeWithException(failure) }
        }
    }
}

/** App-owned factory. The same returned object supplies Home's copies and ReviewSession's kept tour. */
internal class IosTourCacheOwner(
    private val rootDirectory: String = NSHomeDirectory() + "/Library/Application Support/PullyTours-v1",
) {
    private var binding: IosTourCopies? = null
    private var closed = false

    fun forSource(namespace: String, current: () -> Boolean): IosTourCopies = TourFileOwners.locked {
        check(!closed) { "The tour cache owner has closed." }
        require(namespace.length == 64 && namespace.all { it in '0'..'9' || it in 'a'..'f' })
        if (!current()) throw CancellationException("The tour cache's source changed.")
        binding?.takeIf { it.namespace == namespace && it.current }?.let { return@locked it }
        binding?.retire(purge = binding?.namespace != namespace)
        IosTourCopies(rootDirectory, namespace, current).also { binding = it }
    }

    /** Called before replacement credentials publish; an unbound startup has read no private tour. */
    fun invalidateSource() = TourFileOwners.locked {
        binding?.retire(purge = true)
        binding = null
    }

    /** Scene destruction revokes handles but keeps files for a verified next launch. */
    fun close() = TourFileOwners.locked {
        closed = true
        binding?.retire(purge = false)
        binding = null
    }
}

/** Foundation is only the file edge; parsing, de-duplication and the 200-tour policy are shared with Android. */
internal class IosTourCopies internal constructor(
    private val root: String,
    internal val namespace: String,
    private val valid: () -> Boolean,
) : SessionTourCache, HomeTourCopies {
    init {
        require(root.startsWith("/"))
        require(namespace.length == 64 && namespace.all { it in '0'..'9' || it in 'a'..'f' })
    }
    private val lease = TourFileOwners.locked { TourFileOwners.acquire(root) }
    private var retired = false
    internal val current: Boolean get() = !retired && TourFileOwners.current(root, lease) && valid()
    private val directory = "$root/$namespace"
    private var lastWritten: String? = null
    private val manager get() = NSFileManager.defaultManager
    private fun path(pull: String) = "$directory/${LinkPlaces.fileAnchor(pull)}.json"
    private val files = object : StoredTourFiles {
        override fun read(pull: String): String? {
            if (!current) return null
            val name = path(pull)
            val file = NSFileHandle.fileHandleForReadingAtPath(name) ?: return null
            val data = try { file.readDataOfLength(MAX_FILE_BYTES.toULong() + 1uL) } finally { file.closeFile() }
            if (!current) return null
            if (data.length > MAX_FILE_BYTES.toULong()) {
                manager.removeItemAtPath(name, error = null)
                return null
            }
            return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().also {
                if (it == null && current) manager.removeItemAtPath(name, error = null)
            }
        }
        override fun exists(pull: String): Boolean = current && manager.fileExistsAtPath(path(pull))
        override fun remove(pull: String) { if (current) manager.removeItemAtPath(path(pull), error = null) }
        override fun write(pull: String, tour: String) {
            if (!current || tour.encodeToByteArray().size > MAX_FILE_BYTES) return
            check(manager.createDirectoryAtPath(directory, true, null, null)) { "The tour cache directory could not be created." }
            if (!current) return
            check(NSString.create(string = tour).writeToFile(path(pull), atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
                "The tour cache could not be saved."
            }
            lastWritten = path(pull)
        }
        override fun prune(limit: Int) {
            if (!current) return
            val files = (manager.contentsOfDirectoryAtPath(root, error = null) as? List<*>)
                .orEmpty().filterIsInstance<String>().filter(::opaqueName).flatMap { source ->
                    (manager.contentsOfDirectoryAtPath("$root/$source", error = null) as? List<*>)
                        .orEmpty().filterIsInstance<String>().filter { it.endsWith(".json") && opaqueName(it.removeSuffix(".json")) }
                        .mapNotNull { name ->
                            val path = "$root/$source/$name"
                            val attributes = manager.attributesOfItemAtPath(path, error = null) ?: return@mapNotNull null
                            val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return@mapNotNull null
                            val at = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
                            KeptFile(path, bytes, at)
                        }
                }.sortedWith(compareByDescending<KeptFile> { it.path == lastWritten }.thenByDescending { it.at }.thenBy { it.path })
            var bytes = 0L
            files.forEachIndexed { index, file ->
                if (index >= limit || file.bytes > MAX_FILE_BYTES || bytes + file.bytes > MAX_TOTAL_BYTES) manager.removeItemAtPath(file.path, error = null)
                else bytes += file.bytes
            }
        }
    }
    private val stored = StoredTours(files) { current }

    init {
        // A process may end after its atomic write and before pruning. Reopening must repair that budget too.
        TourFileOwners.locked { if (current) files.prune(StoredTours.LIMIT) }
    }

    override fun get(pull: String): Tour? = TourFileOwners.locked { stored.get(pull) }
    override suspend fun contains(pull: String): Boolean = TourFileOwners.onFiles { stored.get(pull) != null }
    override suspend fun put(pull: String, tour: JsonObject) = TourFileOwners.onFiles {
        if (current && tour.toString().encodeToByteArray().size <= MAX_FILE_BYTES) stored.put(pull, tour)
    }

    internal fun retire(purge: Boolean) {
        if (retired) return
        retired = true
        stored.clearMemory()
        if (!TourFileOwners.current(root, lease)) return
        TourFileOwners.release(root, lease)
        if (purge) manager.removeItemAtPath(directory, error = null)
    }

    private data class KeptFile(val path: String, val bytes: Long, val at: Double)
    private fun opaqueName(name: String) = name.length == 64 && name.all { it in '0'..'9' || it in 'a'..'f' }
    companion object {
        internal const val MAX_FILE_BYTES = 4 * 1024 * 1024
        internal const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
