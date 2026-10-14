package com.octaviantocan.pulls.core

/** A pull request the Inbox already lists: enough to find it by its number. */
data class ListedPull(val owner: String, val repo: String, val number: Int)

/** `42` or `#42`, typed or pasted on its own. Anything else is `null`, so `owner/repo#42` stays a link. */
fun searchNumber(text: String): Int? =
    Regex("""^#?(\d{1,7})$""").matchEntire(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

/**
 * The pull request a bare [number] names, or `null` when none of the [listed] ones has it or several do.
 * A number alone cannot say which repository, so then the search only filters the list.
 */
fun listedPullByNumber(number: Int, listed: List<ListedPull>): ListedPull? =
    listed.filter { it.number == number }.distinct().singleOrNull()
