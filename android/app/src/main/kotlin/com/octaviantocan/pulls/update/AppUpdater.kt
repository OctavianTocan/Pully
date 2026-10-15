package com.octaviantocan.pulls.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.isNewerThan
import com.octaviantocan.pulls.core.parseUpdateManifest
import com.octaviantocan.pulls.core.sha256Hex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The downloaded APK is not the one the manifest names: cut short, or replaced on the way. */
class DigestMismatch(message: String) : IllegalStateException(message)

/**
 * Checks `{server}/android/latest.json`, downloads the APK, checks the SHA-256, and
 * hands the bytes to the platform installer. There is no updater library in this path.
 */
class AppUpdater(private val context: Context) {
    fun check(server: String, headers: Map<String, String>, installedVersionCode: Int): UpdateManifest? {
        val manifest = parseUpdateManifest(get("${server.trimEnd('/')}/android/latest.json", headers))
        return manifest.takeIf { it.isNewerThan(installedVersionCode) }
    }

    fun download(manifest: UpdateManifest, headers: Map<String, String>, onProgress: (Long, Long) -> Unit): File {
        val connection = open(manifest.apkUrl, headers)
        val total = connection.contentLengthLong
        val file = File(context.cacheDir, "pulls-update.apk")
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    readTotal += n
                    onProgress(readTotal, total)
                }
            }
        }
        val actual = sha256Hex(file.readBytes())
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            file.delete()
            throw DigestMismatch("Downloaded APK digest $actual does not match ${manifest.sha256}")
        }
        return file
    }

    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val connection = open(url, headers)
        return connection.inputStream.bufferedReader().readText()
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        if (connection.responseCode !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            error("Update request failed: HTTP ${connection.responseCode} $detail")
        }
        return connection
    }
}
