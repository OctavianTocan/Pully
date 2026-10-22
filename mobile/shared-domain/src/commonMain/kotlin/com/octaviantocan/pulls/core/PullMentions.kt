package com.octaviantocan.pulls.core

/*
 * How an answer about the inbox names pull requests: `owner/repo#12`, or a link to the pull request on GitHub.
 * Only the ones the inbox lists count, so a name the model made up stays plain text.
 */

/** `owner/repo#12` in running text. */
val PULL_MENTION = Regex("""(?<![\w/.-])([A-Za-z0-9][\w.-]*)/([\w.-]+)#(\d+)\b""")

/** A pull request's page on GitHub. */
private val PULL_URL = Regex("""^https?://(?:www\.)?github\.com/([^/\s]+)/([^/\s]+)/pull/(\d+)(?:[/?#].*)?$""")

/** The listed pull request [owner]/[repo]#[number] names, ignoring case as GitHub does, or `null`. */
fun listedPull(owner: String, repo: String, number: Int, listed: List<ListedPull>): ListedPull? =
    listed.firstOrNull { it.number == number && it.owner.equals(owner, ignoreCase = true) && it.repo.equals(repo, ignoreCase = true) }

/** The pull request a link to its page on GitHub points at, or `null` for any other link. */
fun pullRefOfUrl(url: String): PullRef? {
    val match = PULL_URL.matchEntire(url.trim()) ?: return null
    val (owner, repo, number) = match.destructured
    return number.toIntOrNull()?.let { PullRef(owner, repo, it) }
}

/** The listed pull request a GitHub link points at, or `null`. */
fun listedPullOfUrl(url: String, listed: List<ListedPull>): ListedPull? =
    pullRefOfUrl(url)?.let { listedPull(it.owner, it.repo, it.number, listed) }

/** The listed pull requests [text] names, as links or as `owner/repo#12`, in the order it first names them. */
fun mentionedPulls(text: String, listed: List<ListedPull>): List<ListedPull> {
    val found = LinkedHashSet<ListedPull>()
    val links = Regex("""\]\(([^)\s]+)\)""").findAll(text).map { it.range.first to listedPullOfUrl(it.groupValues[1], listed) }
    val names = PULL_MENTION.findAll(text).map { m -> m.range.first to m.groupValues[3].toIntOrNull()?.let { listedPull(m.groupValues[1], m.groupValues[2], it, listed) } }
    (links + names).sortedBy { it.first }.forEach { (_, pull) -> if (pull != null) found += pull }
    return found.toList()
}
