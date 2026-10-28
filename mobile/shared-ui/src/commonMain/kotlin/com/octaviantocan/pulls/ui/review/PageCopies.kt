package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.PullPage

/**
 * Pull request pages kept on the phone ([PullPage]), by the pull request's label, so one opens from the copy with
 * nothing to wait for (`pages-are-read-before-the-tap`). The host hands each account and server a store of its own, so
 * a page never reaches another account; a store may drop pages to stay within its size.
 */
interface PageCopies {
    fun read(name: String): PullPage?
    fun write(name: String, page: PullPage)
    /** The [PullPage.version] of the page kept for [name], without reading the page; null when none is kept. */
    fun version(name: String): String?
}

/** Pages kept in memory only, the newest [limit] of them: for hosts without a disk store, and tests. */
class MemoryPageCopies(private val limit: Int = 8) : PageCopies {
    private val pages = linkedMapOf<String, PullPage>()

    override fun read(name: String): PullPage? = pages.remove(name)?.also { pages[name] = it }

    override fun version(name: String): String? = pages[name]?.version

    override fun write(name: String, page: PullPage) {
        pages.remove(name)
        pages[name] = page
        while (pages.size > limit) pages.remove(pages.keys.first())
    }
}
