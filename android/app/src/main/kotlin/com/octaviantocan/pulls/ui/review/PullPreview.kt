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
 * The preview for [target], from the Inbox copy kept on this device, else from the Tours list. `null` for anything
 * that is not a pull request, or one the phone has never listed (a pasted link): the page then shows only its number.
 */
internal fun pullPreview(context: Context, target: OpenTarget): PullPreview? {
    val pull = target as? OpenTarget.Pull ?: return null
    val home = deviceStore(context)
    val snapshots = listOf("open", "closed").mapNotNull { state ->
        val raw = home.get(MyPullsSync.STORAGE_PREFIX + state) ?: return@mapNotNull null
        runCatching { (Json.parseToJsonElement(raw) as? JsonObject)?.asSnapshot() }.getOrNull()
    }
    pullPreview(pull, snapshots)?.let { return it }
    val tour = TourIndex(deviceStore(context, "pulls-inbox")).get("${pull.owner}/${pull.repo}#${pull.number}")
    return tour?.prTitle?.takeIf { it.isNotBlank() }?.let { PullPreview(it) }
}
