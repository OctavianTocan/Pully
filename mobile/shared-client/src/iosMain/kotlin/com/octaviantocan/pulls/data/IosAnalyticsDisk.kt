@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.data

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/** One serial file queue across source lifetimes, matching the existing Android executor. */
internal object IosAnalyticsFiles {
    private val guard = AnalyticsLock()
    private var epoch = 0L
    private val namespaces = mutableMapOf<String, Long>()
    // A serial GCD queue is FIFO. NSOperationQueue's concurrency limit alone is not.
    private val worker = dispatch_queue_create("com.octaviantocan.pully.first-party-usage-files", null)
    val io = AnalyticsIo { work -> dispatch_async(worker) { work() } }
    val root: String get() = NSHomeDirectory() + "/Library/Application Support/PullyAnalytics"
    fun token(): Long = guard.withLock { epoch }
    fun invalidateWrites() { guard.withLock { epoch++; namespaces.clear() } }
    fun acquire(namespace: String): Long = (namespaces[namespace] ?: 0L).plus(1).also { namespaces[namespace] = it }
    fun owns(namespace: String, lease: Long): Boolean = namespaces[namespace] == lease
    fun <T> locked(block: () -> T): T = guard.withLock(block)
    fun valid(token: Long): Boolean = token == epoch && usageAllowed()
    private fun usageAllowed(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        return defaults.objectForKey("pulls-ui.usageStats") == null || defaults.boolForKey("pulls-ui.usageStats")
    }
    fun dropAll() {
        io.execute { locked { NSFileManager.defaultManager.removeItemAtPath(root, error = null) } }
    }
}

/** A private, opaque source namespace; no URL, account or credential bytes appear in its filename. */
internal class IosAnalyticsDisk(private val namespace: String) : AnalyticsDisk {
    private val epoch = IosAnalyticsFiles.token()
    private var lease: Long? = null
    private val path: String get() = "${IosAnalyticsFiles.root}/$namespace.ndjson"
    init { require(namespace.length == 64 && namespace.all { it in '0'..'9' || it in 'a'..'f' }) }

    override fun read(): List<String> = IosAnalyticsFiles.locked {
        if (!IosAnalyticsFiles.valid(epoch)) return@locked emptyList()
        // Activation happens on the serial file worker, after the old queue's final write.
        if (lease == null) lease = IosAnalyticsFiles.acquire(namespace)
        if (!IosAnalyticsFiles.owns(namespace, lease!!)) return@locked emptyList()
        val file = NSFileHandle.fileHandleForReadingAtPath(path) ?: return@locked emptyList()
        val data = try { file.readDataOfLength(48uL * 1024uL * 1024uL + 1uL) } finally { file.closeFile() }
        if (data.length > 48uL * 1024uL * 1024uL) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            return@locked emptyList()
        }
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()?.lineSequence()
            ?.filter { it.isNotBlank() }?.toList().orEmpty()
    }

    override fun replace(lines: List<String>) {
        IosAnalyticsFiles.locked {
            if (!IosAnalyticsFiles.valid(epoch) || lease == null || !IosAnalyticsFiles.owns(namespace, lease!!)) return@locked
            if (lines.isEmpty()) {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                return@locked
            }
            check(NSFileManager.defaultManager.createDirectoryAtPath(IosAnalyticsFiles.root, true, null, null)) {
                "The private usage queue directory could not be created."
            }
            check(NSString.create(string = lines.joinToString("\n", postfix = "\n"))
                .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
                "The private usage queue could not be saved."
            }
        }
    }

    private fun key(name: String): String = if (name == "device") "pulls-analytics.device" else "pulls-analytics.$namespace.$name"
    override fun metadata(name: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key(name))
    override fun metadata(name: String, value: String?) {
        if (value == null) NSUserDefaults.standardUserDefaults.removeObjectForKey(key(name))
        else NSUserDefaults.standardUserDefaults.setObject(value, forKey = key(name))
    }
}
