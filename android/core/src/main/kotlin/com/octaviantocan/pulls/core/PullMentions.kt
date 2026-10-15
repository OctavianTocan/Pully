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

/**
 * The pull request an inbox answer names, when the inbox knows it: one [listed] holds, or another number in a repository
 * it holds, spelled as the list spells it. The answer also reads pull requests closed lately, which the phone's list may
 * not hold; a repository the inbox does not have stays unknown.
 */
fun knownPull(owner: String, repo: String, number: Int, listed: List<ListedPull>): ListedPull? =
    listedPull(owner, repo, number, listed)
        ?: listed.firstOrNull { it.owner.equals(owner, ignoreCase = true) && it.repo.equals(repo, ignoreCase = true) }?.let { ListedPull(it.owner, it.repo, number) }

/** The pull requests [text] names that [resolve] knows, as links or as `owner/repo#12`, in the order it first names them. */
fun mentionedPulls(text: String, resolve: (owner: String, repo: String, number: Int) -> ListedPull?): List<ListedPull> {
    val found = LinkedHashSet<ListedPull>()
    val links = Regex("""\]\(([^)\s]+)\)""").findAll(text).map { it.range.first to pullRefOfUrl(it.groupValues[1])?.let { ref -> resolve(ref.owner, ref.repo, ref.number) } }
    val names = PULL_MENTION.findAll(text).map { m -> m.range.first to m.groupValues[3].toIntOrNull()?.let { resolve(m.groupValues[1], m.groupValues[2], it) } }
    (links + names).sortedBy { it.first }.forEach { (_, pull) -> if (pull != null) found += pull }
    return found.toList()
}

/** The listed pull requests [text] names, in the order it first names them. */
fun mentionedPulls(text: String, listed: List<ListedPull>): List<ListedPull> = mentionedPulls(text) { owner, repo, number -> listedPull(owner, repo, number, listed) }
