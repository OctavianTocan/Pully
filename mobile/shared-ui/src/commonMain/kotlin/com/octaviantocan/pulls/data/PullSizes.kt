package com.octaviantocan.pulls.data

/**
 * How big each pull request is, as the lists that showed it said (the Inbox, a repository's page): added and removed
 * lines and files. A pull request page shows these at once, with the rest of its first screen, instead of waiting
 * for its diff to count them (H29); the diff's own count takes over when it lands.
 */
object PullSizes {
    data class Size(val additions: Int, val deletions: Int, val files: Int?)

    private val guard = PullSizeGuard()
    private val sizes = LinkedHashMap<String, Size>()
    private var source: String? = null
    private var generation = 0L
    private var boundedSource = false
    internal const val MAX_SIZES = 1_024

    private fun key(owner: String, repo: String, number: Int) = "$owner/$repo#$number".lowercase()

    /** A list that does not know the size forgets the old one, which may be an earlier revision's. */
    fun put(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) {
        guard.locked { putSize(owner, repo, number, additions, deletions, files) }
    }

    fun get(owner: String, repo: String, number: Int): Size? = guard.locked { sizes[key(owner, repo, number)] }

    /** Drops all first-frame counts on an explicit native source/account invalidation. */
    fun clear(): Unit = guard.locked { sizes.clear(); source = null; boundedSource = false; generation++ }

    /** A native host holds one lease for its current opaque endpoint/account/Access identity. */
    fun source(identity: String, bounded: Boolean = true): SourceLease = guard.locked {
        if (source != identity || boundedSource != bounded) {
            sizes.clear(); source = identity; boundedSource = bounded; generation++
        }
        SourceLease(generation)
    }

    class SourceLease internal constructor(private val owned: Long) {
        fun put(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) = guard.locked {
            if (owned == generation) putSize(owner, repo, number, additions, deletions, files)
        }

        fun get(owner: String, repo: String, number: Int): Size? = guard.locked {
            if (owned == generation) sizes[key(owner, repo, number)] else null
        }

        fun invalidate(): Unit = guard.locked {
            if (owned == generation) { sizes.clear(); source = null; boundedSource = false; generation++ }
        }
    }

    private fun putSize(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) {
        val key = key(owner, repo, number)
        sizes.remove(key)
        if (additions != null && deletions != null) sizes[key] = Size(additions, deletions, files)
        while (boundedSource && sizes.size > MAX_SIZES) sizes.remove(sizes.keys.first())
    }
}
