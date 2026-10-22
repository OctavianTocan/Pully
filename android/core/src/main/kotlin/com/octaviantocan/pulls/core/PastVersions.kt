package com.octaviantocan.pulls.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One past version of the app as the server lists it at `/download/past/index.json`: rebuilt from [commit] by
 * `android/scripts/build-past-ui.py` as its own app, [packageName], beside the current one. [versionCode] is the
 * version's own, not the offset one the rebuilt app carries.
 */
@Serializable
data class PastVersion(
    val versionName: String,
    val versionCode: Int,
    val packageName: String,
    val commit: String = "",
    val date: String = "",
    val summary: String = "",
    val sha256: String,
    val size: Long = 0,
)

@Serializable
private data class PastIndex(val versions: List<PastVersion> = emptyList())

private val pastJson = Json { ignoreUnknownKeys = true }

/**
 * The past versions in [text], oldest first, without any at or above [currentCode]: the app the phone runs is the
 * slider's newest stop, and a server ahead of the phone could list versions that are not past for it.
 */
fun parsePastVersions(text: String, currentCode: Int): List<PastVersion> =
    pastJson.decodeFromString<PastIndex>(text).versions
        .filter { it.versionCode < currentCode && it.packageName.startsWith("com.octaviantocan.pulls.v") }
        .distinctBy { it.versionName }
        .sortedBy { it.versionCode }
