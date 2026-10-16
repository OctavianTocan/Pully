package com.octaviantocan.pulls.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.isNewerThan
import com.octaviantocan.pulls.core.openUpdateConnection
import com.octaviantocan.pulls.core.parseUpdateManifest
import com.octaviantocan.pulls.core.sha256Hex
import java.io.File

/** The downloaded APK is not the one the manifest names: cut short, or replaced on the way. */
class DigestMismatch(message: String) : IllegalStateException(message)

/**
 * Checks `{server}/android/latest.json`, downloads the APK, checks the SHA-256, and
 * hands the bytes to the platform installer. There is no updater library in this path.
 */
class AppUpdater(private val context: Context) {
    fun check(server: String, headers: Map<String, String>, installedVersionCode: Int): UpdateManifest? {
        val manifest = parseUpdateManifest(get(server, "android/latest.json", headers))
        return manifest.takeIf { it.isNewerThan(installedVersionCode) }
    }

    fun download(server: String, manifest: UpdateManifest, headers: Map<String, String>, onProgress: (Long, Long) -> Unit): File =
        download(server, manifest.apkUrl, manifest.sha256, headers, File(context.cacheDir, "pulls-update.apk"), onProgress)

    /** Downloads [url] to [file] and checks it against [sha256]; a mismatch deletes the file and throws [DigestMismatch]. */
    fun download(server: String, url: String, sha256: String, headers: Map<String, String>, file: File, onProgress: (Long, Long) -> Unit): File {
        val connection = openUpdateConnection(server, url, headers)
        try {
            val total = connection.contentLengthLong
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
        } finally {
            connection.disconnect()
        }
        val actual = sha256Hex(file.readBytes())
        if (!actual.equals(sha256, ignoreCase = true)) {
            file.delete()
            throw DigestMismatch("Downloaded APK digest $actual does not match $sha256")
        }
        return file
    }

    /**
     * Hands [apk] to the system installer. With [packageName] the installer refuses an APK of any other package, which
     * is how a past version can never replace Pully itself; its result goes to [PastVersions] instead of [Updates].
     */
    fun install(apk: File, packageName: String? = null) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (packageName != null) params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).putExtra(UpdateInstallReceiver.EXTRA_PAST, packageName != null)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    fun get(server: String, location: String, headers: Map<String, String>): String {
        val connection = openUpdateConnection(server, location, headers)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
