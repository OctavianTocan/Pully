package com.octaviantocan.pulls.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class DigestMismatch : IOException("Downloaded APK does not match the update manifest.")

/** Called once at process start, before this process can own a transfer. */
fun clearInterruptedUpdateDownloads(directory: File) {
    directory.listFiles()?.filter { it.isFile && it.name.startsWith("pulls-update-") && it.name.endsWith(".part") }?.forEach { it.delete() }
}

/** Only a complete, verified download replaces [file]. Every failed attempt removes its private partial file. */
fun downloadUpdate(
    server: String,
    url: String,
    sha256: String,
    headers: Map<String, String>,
    file: File,
    timeouts: UpdateTimeouts = UpdateTimeouts(),
    onProgress: (Long, Long) -> Unit,
): File {
    require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid update digest." }
    val partial = File.createTempFile("pulls-update-", ".part", file.absoluteFile.parentFile)
    try {
        withUpdateConnection(server, url, headers, timeouts) { connection ->
            val digest = MessageDigest.getInstance("SHA-256")
            val total = connection.contentLengthLong
            var downloaded = 0L
            onProgress(downloaded, total)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            if (total >= 0 && total != downloaded) throw IOException("Update download was incomplete.")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) throw DigestMismatch()
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    } finally {
        partial.delete()
    }
}
