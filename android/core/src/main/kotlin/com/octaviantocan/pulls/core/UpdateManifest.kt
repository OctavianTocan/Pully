package com.octaviantocan.pulls.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** What the server publishes at `/android/latest.json`. The app compares [versionCode] with its own. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val apkUrl: String,
    val notes: String = "",
)

private val updateJson = Json { ignoreUnknownKeys = true }

fun parseUpdateManifest(text: String): UpdateManifest = updateJson.decodeFromString(text)

fun UpdateManifest.isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

/** Lower-case hex SHA-256. The updater refuses an APK whose digest does not match the manifest. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
