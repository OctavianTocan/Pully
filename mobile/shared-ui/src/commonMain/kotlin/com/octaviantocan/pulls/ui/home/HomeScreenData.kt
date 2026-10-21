package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.data.RpcSource

/** One native source owns the shared first pages, posted list, profile and live repository stores. */
class HomeScreenData(
    private val repo: Repo,
    val pages: FirstPages,
    val posted: PostedDiffsStore,
    val stats: GithubStatsStore,
    private val currentSource: () -> Boolean,
    val effects: HomeScreenEffects,
    val sizes: PullSizes.SourceLease,
) {
    private val stores = mutableSetOf<RepoPullsStore>()
    private var invalidated = false
    val current: Boolean get() = !invalidated && currentSource()

    fun repository(owner: String, name: String): RepoPullsStore =
        RepoPullsStore(repo, owner, name, pages, { current }, effects).also { stores += it }

    fun release(store: RepoPullsStore) { stores -= store; store.invalidate() }

    fun invalidate() {
        invalidated = true
        sizes.invalidate()
        stores.toList().forEach(RepoPullsStore::invalidate)
        stores.clear(); pages.invalidate(); posted.invalidate(); stats.invalidate()
    }
}

/** Explicit application owner; no static cache can be reused by another account or authorization. */
class HomeScreenDataOwner {
    private var source: RpcSource? = null
    private var epoch: Long? = null
    private var data: HomeScreenData? = null

    fun bind(source: RpcSource?, epoch: Long, create: () -> HomeScreenData): HomeScreenData {
        if (this.source == source && this.epoch == epoch) data?.let { return it }
        clear()
        return create().also { this.source = source; this.epoch = epoch; data = it }
    }

    fun clear() { data?.invalidate(); data = null; source = null; epoch = null }
}
