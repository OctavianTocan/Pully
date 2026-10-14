package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `RepoInfo` from `local/pages.ts`. */
data class RepoInfo(
    val currentBranch: String?,
    val defaultBranch: Pair<String, String>?,
    val branches: List<String>,
    val tags: List<String>,
    val commits: List<Pair<String, String>>,
) {
    /** The checked-out branch, when it isn't the default branch itself. */
    val shownCurrentBranch: String? get() = currentBranch?.takeIf { it != defaultBranch?.first }

    /** Most recently committed first, without the default branch; [limit] of them. */
    fun pickerBranches(limit: Int = 10): List<String> = branches.filter { it != defaultBranch?.first }.take(limit)

    /** What a `base...head` compare opens: a merge-base range on the server's checkout (`checkout.vue`). */
    fun compareTarget(base: String, head: String): OpenTarget? {
        val b = base.trim()
        val h = head.trim()
        if (b.isEmpty() || h.isEmpty() || b.startsWith("-") || h.startsWith("-")) return null
        return OpenTarget.Local("$b...$h")
    }
}

fun JsonObject.asRepoInfo(): RepoInfo {
    fun s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun list(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val def = this["defaultBranch"] as? JsonObject
    return RepoInfo(
        currentBranch = s("currentBranch"),
        defaultBranch = def?.let { ((it["name"] as? JsonPrimitive)?.contentOrNull ?: "") to ((it["ref"] as? JsonPrimitive)?.contentOrNull ?: "") },
        branches = list("branches"),
        tags = list("tags"),
        commits = (this["commits"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { ((it["sha"] as? JsonPrimitive)?.contentOrNull ?: "") to ((it["subject"] as? JsonPrimitive)?.contentOrNull ?: "") }
        },
    )
}

private const val BRANCH_LIMIT = 10

/** Branches and tags whose name contains what was typed (the first few while it is blank), none once it names one exactly. */
internal fun refSuggestions(value: String, names: List<String>, limit: Int = 8): List<String> {
    val q = value.trim().lowercase()
    if (q.isNotEmpty() && names.any { it == value.trim() }) return emptyList()
    return names.filter { it.lowercase().contains(q) }.take(limit)
}

/**
 * Review local changes, in the Repo list's look: a back circle and the title, then white cards on the paper.
 * Compare takes two refs with matching names offered under each; the shortcuts, branches and recent commits open as rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CheckoutScreen(nav: Nav) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = LocalPullsColors.current
    var info by remember { mutableStateOf<RepoInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var base by rememberSaveable { mutableStateOf("") }
    var head by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(attempt) {
        error = null
        try {
            val loaded = Repo(context).repoInfo().asRepoInfo()
            info = loaded
            if (base.isEmpty()) base = loaded.defaultBranch?.second.orEmpty()
            if (head.isEmpty()) head = loaded.currentBranch ?: "HEAD"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val names = remember(info) { info?.let { i -> i.branches + i.tags }.orEmpty() }
    val target = info?.compareTarget(base, head)

    Column(Modifier.fillMaxSize().background(c.base)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
            FloatIcon(Lu.back, "Back", nav.back, iconSize = 22.dp)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("local.picker.title"), style = PullsType.xxl, color = c.text)
                Text(t("local.picker.tagline"), style = PullsType.sm, color = c.muted)
            }
            SheetCard {
                SheetField(t("local.picker.base"), base, { base = it }, placeholder = "main", first = true)
                Suggestions(refSuggestions(base, names)) { base = it }
                SheetField(t("local.picker.head"), head, { head = it }, placeholder = "HEAD")
                Suggestions(refSuggestions(head, names)) { head = it }
            }
            CtaButton(t("local.picker.compare"), { target?.let(nav.openTarget) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = target != null)
            if (info != null && target == null && base.isNotBlank() && head.isNotBlank()) {
                Text(
                    "The phone can compare a branch against ${info?.defaultBranch?.second ?: "the default branch"}; other ranges open on the web.",
                    Modifier.padding(top = 8.dp), style = PullsType.sm, color = c.muted,
                )
            }
            val i = info
            when {
                // No loading screen: the shape of the rows, replaced in place when they arrive.
                i == null && error == null -> SheetCard {
                    repeat(4) { Column(Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) { Skeleton(Modifier.fillMaxWidth(if (it % 2 == 0) 0.6f else 0.4f)) } }
                }
                i == null -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("local.loadFailed"), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                    error?.let { Text(it, style = PullsType.sm, color = c.muted) }
                    PillButton(t("common.retry"), { attempt++ })
                }
                else -> {
                    val current = i.shownCurrentBranch
                    if (i.defaultBranch != null) {
                        SheetCard {
                            if (current != null) {
                                SheetRow(current, { nav.openTarget(OpenTarget.Branch(current)) }, first = true, icon = { LuIcon(Lu.branch, tint = c.text, size = 20.dp) }) {
                                    Text(t("local.picker.against", "base" to i.defaultBranch.second), style = PullsType.sm, color = c.muted, maxLines = 1)
                                }
                            }
                            SheetRow(t("local.picker.worktree"), { nav.openTarget(OpenTarget.Worktree) }, first = current == null, icon = { LuIcon(Lu.diff, tint = c.text, size = 20.dp) }) {
                                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                            }
                        }
                    }
                    val branches = i.pickerBranches(BRANCH_LIMIT)
                    if (branches.isNotEmpty() && i.defaultBranch != null) {
                        Heading(t("local.picker.branches"))
                        SheetCard(Modifier.padding(top = 0.dp)) {
                            branches.forEachIndexed { index, branch ->
                                SheetRow(branch, { nav.openTarget(OpenTarget.Branch(branch)) }, first = index == 0, icon = { LuIcon(Lu.branch, tint = c.text, size = 20.dp) }) {
                                    if (branch == i.shownCurrentBranch) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                                }
                            }
                        }
                    }
                    if (i.commits.isNotEmpty()) {
                        Heading(t("local.picker.recentCommits"))
                        SheetCard(Modifier.padding(top = 0.dp)) {
                            i.commits.forEachIndexed { index, (sha, subject) ->
                                SheetRow(subject, { nav.openTarget(OpenTarget.Local(sha)) }, first = index == 0, iconless = true) {
                                    Text(sha.take(7), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 24.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = LocalPullsColors.current.muted)
}

/** The refs that match what is typed, as chips under the field; one tap fills it. */
@Composable
private fun Suggestions(names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name -> SoftChip(name, false, { onPick(name) }) }
    }
}
