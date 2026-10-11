package com.octaviantocan.pulls.core

import java.security.MessageDigest

/** Port of the web `file-links.ts`: links from one diff file to GitHub. */
object FileLinks {
    /** A file's page on GitHub at a given commit (`githubBlobUrl`). */
    fun blob(owner: String, repo: String, sha: String, path: String): String =
        "https://github.com/$owner/$repo/blob/$sha/" + path.split('/').joinToString("/") { encodeSegment(it) }

    /** GitHub's diff view scrolled to one file (`githubFileDiffUrl`): `#diff-<sha256 of path>`. */
    fun fileDiff(url: String, pullRequest: Boolean, path: String): String {
        val page = if (pullRequest) "$url/files" else url
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return "$page#diff-" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Repository coordinates for a target that lives on GitHub, or null for local diffs. */
    fun repoOf(target: OpenTarget): Pair<String, String>? = when (target) {
        is OpenTarget.Pull -> target.owner to target.repo
        is OpenTarget.Compare -> target.owner to target.repo
        is OpenTarget.Commit -> target.owner to target.repo
        else -> null
    }

    // Same set `encodeURIComponent` leaves alone.
    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "-_.!~*'()")) append(c)
            else append("%%%02X".format(byte.toInt() and 0xFF))
        }
    }
}
