package com.octaviantocan.pulls.core

/**
 * Something the phone can open: a GitHub pull request, a server path, or a local branch.
 * [serverTarget] is the path the pulls.review server already understands.
 */
sealed class OpenTarget {
    abstract val label: String
    abstract val serverTarget: String

    data class Pull(
        val owner: String,
        val repo: String,
        val number: Int,
        val host: String? = null,
    ) : OpenTarget() {
        override val label = "$owner/$repo#$number"
        override val serverTarget = "$owner/$repo/$number"
    }

    data class Branch(val name: String) : OpenTarget() {
        override val label = name
        override val serverTarget = "branch/$name"
    }

    data class Compare(
        val owner: String,
        val repo: String,
        val base: String,
        val head: String,
    ) : OpenTarget() {
        override val label = "$owner/$repo $base...$head"
        override val serverTarget = "$owner/$repo/compare/$base...$head"
    }

    data class Commit(val owner: String, val repo: String, val sha: String) : OpenTarget() {
        override val label = "$owner/$repo@${sha.take(7)}"
        override val serverTarget = "$owner/$repo/commit/$sha"
    }

    /**
     * A local git target in the server's own syntax (`core/src/local/target.ts`):
     * `A...B` a merge-base range, `A..B` a tree diff, anything else one commit.
     */
    data class Local(val target: String) : OpenTarget() {
        override val label = target
        override val serverTarget = "local/$target"
    }

    data object Worktree : OpenTarget() {
        override val label = "Worktree"
        override val serverTarget = ""
    }

    /** A diff an agent posted to the server with the account's key (plan 13), by the id in its `/-/<id>` link. */
    data class Posted(val id: String) : OpenTarget() {
        override val label = "Posted diff"
        override val serverTarget = "-/$id"
    }
}

object PrLinks {
    private val githubPull = Regex(
        """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val serverPull = Regex(
        """(?:https?://)?[^/\s]+/(?:gh/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(\d+)(?:/|\?|#|$)""",
    )
    private val shortPull = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:#|/)(\d+)$""")
    private val githubCompare = Regex(
        """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/compare/(.+?)\.{2,3}([^\s?#]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val githubCommit = Regex(
        """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/commit/([0-9a-fA-F]{7,64})""",
        RegexOption.IGNORE_CASE,
    )
    private val branchPath = Regex("""(?:https?://)?[^/\s]+/branch/(.+?)(?:\?|#|$)""")
    private val bareBranch = Regex("""^branch/(.+)$""")
    /** A posted diff's link, `https://<server>/-/<id>`, on whatever host the server names itself (`PULLY_PUBLIC_URL`). */
    private val postedPath = Regex("""(?:^|https?://)(?:[A-Za-z0-9.-]+(?::\d+)?)?/-/([0-9A-Za-z]{6,32})(?=[/?#\s]|$)""")

    /** The first pull request or branch this text names, or null. */
    fun parse(raw: String): OpenTarget? {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("diff --git")) return null
        postedPath.find(text)?.let { return OpenTarget.Posted(it.groupValues[1]) }
        githubPull.find(text)?.let { match ->
            return OpenTarget.Pull(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt(), "github.com")
        }
        githubCompare.find(text)?.let { match ->
            return OpenTarget.Compare(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        }
        githubCommit.find(text)?.let { match ->
            return OpenTarget.Commit(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        shortPull.matchEntire(text)?.let { match ->
            return OpenTarget.Pull(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
        }
        branchPath.find(text)?.let { return OpenTarget.Branch(it.groupValues[1].trim('/')) }
        bareBranch.matchEntire(text)?.let { return OpenTarget.Branch(it.groupValues[1].trim('/')) }
        // A bare owner/repo/number, or a pulls.review URL. GitHub /pull/ URLs already matched.
        if (!text.contains("/pull/", ignoreCase = true)) {
            serverPull.find(text)?.let { match ->
                return OpenTarget.Pull(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
            }
        }
        return null
    }
}
