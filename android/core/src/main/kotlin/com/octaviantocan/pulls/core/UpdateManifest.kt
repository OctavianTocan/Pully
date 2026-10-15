package com.octaviantocan.pulls.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * What the server publishes at `/android/latest.json`. The app compares [versionCode] with its own. [notes] is
 * the new version's summary and [changelog] the whole `android/CHANGELOG.md` it was built with, so the phone
 * can list every version it has not installed yet. Manifests from before 0.17.0 have no changelog.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val apkUrl: String,
    val notes: String = "",
    val changelog: String = "",
)

/**
 * What the update would bring to a phone on [installedName]: the manifest's changelog sections above it, or, from a
 * manifest without one, a section made of its [UpdateManifest.notes].
 */
fun UpdateManifest.changesSince(installedName: String): List<ChangelogEntry> {
    val listed = parseChangelog(changelog).since(installedName, versionName)
    if (listed.isNotEmpty()) return listed
    return listOf(ChangelogEntry(versionName, notes.trim(), emptyList()))
}

private val updateJson = Json { ignoreUnknownKeys = true }

fun parseUpdateManifest(text: String): UpdateManifest = updateJson.decodeFromString(text)

fun UpdateManifest.isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

/** Lower-case hex SHA-256. The updater refuses an APK whose digest does not match the manifest. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
