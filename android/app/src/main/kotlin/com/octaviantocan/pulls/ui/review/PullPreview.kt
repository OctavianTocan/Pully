package com.octaviantocan.pulls.ui.review

import android.content.Context
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.TourIndex
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.home.deviceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What the phone already knows about a pull request before its page has loaded, so the page can show its title and
 * number at once and fill in the rest as it arrives. [files], [additions] and [deletions] are `null` when unknown.
 */
internal data class PullPreview(
    val title: String,
    val author: String? = null,
    val isDraft: Boolean = false,
    val files: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    /** The branches it merges, `main` and `feature/x`, so the Overview shows them before GitHub answers (J13). */
    val base: String = "",
    val head: String = "",
)

/**
 * The preview for [target], from the Inbox copy kept on this device, else from the Tours list. `null` for anything
 * that is not a pull request, or one the phone has never listed (a pasted link): the page then shows only its number.
 */
internal fun pullPreview(context: Context, target: OpenTarget): PullPreview? {
    val pull = target as? OpenTarget.Pull ?: return null
    val home = deviceStore(context)
    for (state in listOf("open", "closed")) {
        val raw = home.get(MyPullsSync.STORAGE_PREFIX + state) ?: continue
        val snapshot = runCatching { (Json.parseToJsonElement(raw) as? JsonObject)?.asSnapshot() }.getOrNull() ?: continue
        val row = snapshot.pulls.firstOrNull { it.owner.equals(pull.owner, true) && it.repo.equals(pull.repo, true) && it.number == pull.number } ?: continue
        return PullPreview(row.title, row.author.takeIf { it.isNotBlank() }, row.isDraft, row.changedFiles, row.additions, row.deletions, row.baseRef, row.headRef)
    }
    val tour = TourIndex(deviceStore(context, "pulls-inbox")).get("${pull.owner}/${pull.repo}#${pull.number}")
    return tour?.prTitle?.takeIf { it.isNotBlank() }?.let { PullPreview(it) }
}
