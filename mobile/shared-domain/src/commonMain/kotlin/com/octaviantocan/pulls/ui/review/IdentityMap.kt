package com.octaviantocan.pulls.ui.review

/** Line emphasis belongs to a row object, even when another row has equal data. */
class IdentityMap<K : Any, V> {
    private class Entry<K, V>(val key: K, var value: V)
    private val buckets = HashMap<Int, MutableList<Entry<K, V>>>()

    operator fun get(key: K): V? = buckets[diffIdentityHashCode(key)]?.firstOrNull { it.key === key }?.value

    operator fun set(key: K, value: V) {
        val bucket = buckets.getOrPut(diffIdentityHashCode(key)) { mutableListOf() }
        val existing = bucket.firstOrNull { it.key === key }
        if (existing != null) existing.value = value else bucket += Entry(key, value)
    }
}

internal expect fun diffIdentityHashCode(value: Any): Int
internal expect fun compareDiffPaths(first: String, second: String): Int
