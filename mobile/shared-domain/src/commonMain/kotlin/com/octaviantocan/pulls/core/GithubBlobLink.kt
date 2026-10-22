package com.octaviantocan.pulls.core

/** The existing GitHub file-link policy, shared by selected-line and full-file destinations. */
fun githubBlobLink(owner: String, repo: String, sha: String, path: String): String =
    "https://github.com/$owner/$repo/blob/$sha/" + path.split('/').joinToString("/") { encodeSegment(it) }

private const val HEX = "0123456789ABCDEF"

// Same set `encodeURIComponent` leaves alone.
private fun encodeSegment(segment: String): String = buildString {
    for (byte in segment.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (byte >= 0 && (c.isLetterOrDigit() || c in "-_.!~*'()")) append(c)
        else {
            val unsigned = byte.toInt() and 0xFF
            append('%'); append(HEX[unsigned ushr 4]); append(HEX[unsigned and 15])
        }
    }
}

/** Repository coordinates for a target that lives on GitHub, or null for local diffs. */
fun githubRepository(target: OpenTarget): Pair<String, String>? = when (target) {
    is OpenTarget.Pull -> target.owner to target.repo
    is OpenTarget.Compare -> target.owner to target.repo
    is OpenTarget.Commit -> target.owner to target.repo
    else -> null
}
