package com.octaviantocan.pulls.data

import java.util.concurrent.ConcurrentHashMap

/**
 * How big each pull request is, as the lists that showed it said (the Inbox, a repository's page): added and removed
 * lines and files. A pull request page shows these at once, with the rest of its first screen, instead of waiting
 * for its diff to count them (H29); the diff's own count takes over when it lands.
 */
object PullSizes {
    data class Size(val additions: Int, val deletions: Int, val files: Int?)

    private val sizes = ConcurrentHashMap<String, Size>()

    private fun key(owner: String, repo: String, number: Int) = "$owner/$repo#$number".lowercase()

    fun put(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) {
        if (additions == null || deletions == null) return
        sizes[key(owner, repo, number)] = Size(additions, deletions, files)
    }

    fun get(owner: String, repo: String, number: Int): Size? = sizes[key(owner, repo, number)]
}
