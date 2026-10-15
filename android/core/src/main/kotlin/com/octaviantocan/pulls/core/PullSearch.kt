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

/** Words a question to Pulls starts with, when it does not end in a question mark. */
private val QUESTION_WORDS = setOf(
    "what", "which", "who", "whose", "why", "how", "when", "where", "is", "are", "was", "were", "do", "does", "did",
    "can", "could", "should", "will", "would", "has", "have", "any", "anything", "list", "show", "summarize", "summarise",
    "tell", "explain", "find",
)

/**
 * Whether the search text reads as a question for Pulls rather than words to filter the list by: it ends in a
 * question mark, or it is at least three words and starts like a question ("what is failing", "show my drafts").
 * The keyboard's action asks it; anything else only filters, and the Ask pill still asks it on a tap.
 */
fun looksLikeQuestion(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.endsWith("?") || trimmed.endsWith("？")) return trimmed.length > 1
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.size >= 3 && words.first().lowercase().trimEnd(',', ':') in QUESTION_WORDS
}
