package com.octaviantocan.pulls.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.UpdateTimeouts
import com.octaviantocan.pulls.core.downloadUpdate
import com.octaviantocan.pulls.core.isNewerThan
import com.octaviantocan.pulls.core.withUpdateConnection
import com.octaviantocan.pulls.core.parseUpdateManifest
import java.io.File

/**
 * Checks `{server}/download/latest.json`, downloads the APK, checks the SHA-256, and
 * hands the bytes to the platform installer. There is no updater library in this path.
 */
class AppUpdater(private val context: Context) {
    fun check(server: String, headers: Map<String, String>, installedVersionCode: Int): UpdateManifest? {
        val manifest = parseUpdateManifest(get(server, "download/latest.json", headers))
        return manifest.takeIf { it.isNewerThan(installedVersionCode) }
    }

    fun download(server: String, manifest: UpdateManifest, headers: Map<String, String>, onProgress: (Long, Long) -> Unit): File =
        download(server, manifest.apkUrl, manifest.sha256, headers, File(context.cacheDir, "pulls-update.apk"), onProgress)

    fun download(server: String, url: String, sha256: String, headers: Map<String, String>, file: File, onProgress: (Long, Long) -> Unit): File =
        downloadUpdate(server, url, sha256, headers, file, onProgress = onProgress)

    /**
     * Hands [apk] to the system installer. With [packageName] the installer refuses an APK of any other package, which
     * is how a past version can never replace Pully itself; its result goes to [PastVersions] instead of [Updates].
     */
    fun install(apk: File, packageName: String? = null, expectedVersionCode: Int? = null, beforeCommit: (Int) -> Unit = {}) {
        val expectedPackage = packageName ?: context.packageName
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Downloaded file is not an APK.")
        check(archive.packageName == expectedPackage) { "Downloaded APK has the wrong package." }
        if (expectedVersionCode != null) {
            val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            check(archiveCode == expectedVersionCode.toLong() && archiveCode > AppVersion.code(context)) { "Downloaded APK has the wrong version." }
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(expectedPackage)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        try {
            beforeCommit(sessionId)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                    .putExtra(UpdateInstallReceiver.EXTRA_PAST, packageName != null)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw failure
        }
    }

    fun get(server: String, location: String, headers: Map<String, String>): String {
        return withUpdateConnection(server, location, headers, UpdateTimeouts(totalMs = 90_000)) { connection ->
            connection.inputStream.bufferedReader().use { it.readText() }
        }
    }
}
