package com.octaviantocan.pulls.ui.review

import android.content.Context
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.PullPage
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.nativeStorageNamespace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The account's pages kept on the phone, in a folder of its own under the app's private files; null when the server
 * and account are not known yet. Pages from another account stay in that account's folder and are never read here.
 */
fun androidPageCopies(context: Context): PageCopies? {
    val app = context.applicationContext
    Account.load(app)
    val namespace = nativeStorageNamespace(app) ?: return null
    val epoch = RepoIdentity.generation
    return FilePageCopies(File(app.noBackupFilesDir, "pull-pages-v1/$namespace")) { RepoIdentity.generation == epoch }
}

/**
 * Pull request pages kept as one file each in [dir] (`pages-are-read-before-the-tap`): at most [maxPages] pages and
 * [maxBytes] bytes, the page read or written longest ago dropped first. Pages are written on one background thread,
 * each to a new file put in place whole, so a read never sees half a page; [current] turns the store off once the
 * account it was made for is gone.
 */
class FilePageCopies(
    private val dir: File,
    private val maxPages: Int = 60,
    private val maxBytes: Long = 48L * 1024 * 1024,
    private val writer: Executor = Executors.newSingleThreadExecutor(),
    private val current: () -> Boolean = { true },
) : PageCopies {
    /** Each kept page's version, read from the index once. */
    private val versions: ConcurrentHashMap<String, String> by lazy { readIndex() }

    override fun read(name: String): PullPage? {
        if (!current()) return null
        val file = fileOf(name)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val page = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((page["name"] as? JsonPrimitive)?.content != name) return null
        writer.execute { file.setLastModified(System.currentTimeMillis()) }
        return PullPage(page)
    }

    override fun version(name: String): String? = if (current()) versions[name] else null

    override fun write(name: String, page: PullPage) {
        if (!current()) return
        page.version?.let { versions[name] = it } ?: versions.remove(name)
        writer.execute {
            if (!current()) return@execute
            runCatching {
                dir.mkdirs()
                val file = fileOf(name)
                val next = File(dir, "${file.name}.next")
                next.writeText(JsonObject(page.json + ("name" to JsonPrimitive(name))).toString())
                check(next.renameTo(file)) { "The page could not be put in place." }
                trim()
                writeIndex()
            }
        }
    }

    private fun fileOf(name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.json")
    }

    /** Drops the pages read or written longest ago until the rest fit, and forgets their versions. */
    private fun trim() {
        val pages = dir.listFiles { file -> file.name.endsWith(".json") && file.name != INDEX }?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes = 0L
        val dropped = pages.filterIndexed { index, file ->
            bytes += file.length()
            index >= maxPages || bytes > maxBytes
        }
        if (dropped.isEmpty()) return
        val names = dropped.toSet()
        versions.keys.toList().forEach { name -> if (fileOf(name) in names) versions.remove(name) }
        dropped.forEach { it.delete() }
    }

    private fun readIndex(): ConcurrentHashMap<String, String> {
        val index = ConcurrentHashMap<String, String>()
        runCatching {
            Json.parseToJsonElement(File(dir, INDEX).readText()).jsonObject.forEach { (name, version) ->
                (version as? JsonPrimitive)?.content?.let { if (fileOf(name).exists()) index[name] = it }
            }
        }
        return index
    }

    private fun writeIndex() {
        val next = File(dir, "$INDEX.next")
        next.writeText(JsonObject(versions.mapValues { JsonPrimitive(it.value) }).toString())
        next.renameTo(File(dir, INDEX))
    }

    private companion object {
        const val INDEX = "index.json"
    }
}
