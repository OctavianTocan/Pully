package com.octaviantocan.pulls.ui.home

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.Loading
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
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

/** Port of `pages-local/checkout.vue`: compare refs, the branch and worktree shortcuts, branches and recent commits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckoutScreen(nav: Nav) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val bp = rememberBreakpoints()
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
    val suggestions = remember(info) { info?.let { i -> i.branches.map { it to "git-branch-duotone" } + i.tags.map { it to "tag-duotone" } }.orEmpty() }
    val target = info?.compareTarget(base, head)
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(c.base)) {
        AppHeader(nav, scroll.value > 10)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = ((maxWidth - 1152.dp) / 2).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = side + 24.dp)) {
                Spacer(Modifier.height(if (bp.sm) 80.dp else 64.dp))
                val left: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        LandingHero(if (bp.lg) 56 else if (bp.md) 44 else 36)
                        Text(t("local.picker.tagline"), Modifier.widthIn(max = 448.dp), style = PullsType.sm.copy(lineHeight = 26.sp), color = c.fade)
                        Column(Modifier.widthIn(max = 448.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RefField(base, { base = it }, suggestions, t("local.picker.base"), "git-branch-duotone", Modifier.weight(1f))
                                Text("...", style = PullsType.base.copy(fontFamily = PullsType.mono), color = c.fade)
                                RefField(head, { head = it }, suggestions, t("local.picker.head"), null, Modifier.weight(1f))
                            }
                            ActionButton({ target?.let(nav.openTarget) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Primary, icon = "arrow-right-bold", enabled = target != null) {
                                Text(t("local.picker.compare"))
                            }
                            if (info != null && target == null && base.isNotBlank() && head.isNotBlank()) {
                                Text("The phone can compare a branch against ${info?.defaultBranch?.second ?: "the default branch"}; other ranges open on the web.", style = PullsType.xs, color = c.fade)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(t("local.picker.shortcuts"), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val i = info
                                val current = i?.shownCurrentBranch
                                if (current != null && i.defaultBranch != null) {
                                    DiffPill(current, { nav.openTarget(OpenTarget.Branch(current)) }, extra = t("local.picker.against", "base" to i.defaultBranch.second))
                                }
                                DiffPill(t("local.picker.worktree"), { nav.openTarget(OpenTarget.Worktree) })
                            }
                        }
                    }
                }
                val right: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        val i = info
                        when {
                            i == null && error == null -> Loading(t("common.loading"))
                            i == null -> EmptyState("warning-duotone", t("local.loadFailed"), Modifier.fillMaxWidth(), hint = error) {
                                ActionButton({ attempt++ }, variant = ButtonVariant.Primary) { Text(t("common.retry")) }
                            }
                            else -> {
                                val branches = i.pickerBranches(BRANCH_LIMIT)
                                if (branches.isNotEmpty() && i.defaultBranch != null) Section(t("local.picker.branches")) {
                                    branches.forEach { branch ->
                                        ListRow({ nav.openTarget(OpenTarget.Branch(branch)) }) {
                                            PhIcon("git-branch-duotone", tint = c.fade, size = 16.dp)
                                            Text(branch, Modifier.weight(1f), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (branch == i.shownCurrentBranch) PhIcon("check-circle-duotone", tint = c.accentTeal, size = 16.dp, contentDescription = t("local.picker.current"))
                                        }
                                    }
                                }
                                if (i.commits.isNotEmpty()) Section(t("local.picker.recentCommits")) {
                                    i.commits.forEach { (sha, subject) ->
                                        ListRow({ nav.openTarget(OpenTarget.Local(sha)) }) {
                                            Text(sha.take(7), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
                                            Text(subject, Modifier.weight(1f), style = PullsType.sm, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (bp.lg) {
                    Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                        Box(Modifier.weight(1f)) { left() }
                        Box(Modifier.weight(1f).widthIn(max = 480.dp)) { right() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(48.dp)) { left(); right() }
                }
                Spacer(Modifier.height(if (bp.sm) 80.dp else 64.dp))
                AppFooter()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** `LandingHero`: the four-line mono headline with the accent glyphs. */
@Composable
private fun LandingHero(sizeSp: Int) {
    val c = LocalPullsColors.current
    val style = PullsType.xxl.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.05f).sp, fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, letterSpacing = (-0.025f * sizeSp).sp)
    Column {
        Text(buildAnnotatedString { withStyle(SpanStyle(color = c.accentOrange)) { append("@@") }; append(" pull") }, style = style, color = c.text)
        Text(buildAnnotatedString { append("reviews"); withStyle(SpanStyle(color = c.accentMagenta)) { append(":") } }, style = style, color = c.text)
        Text(buildAnnotatedString { withStyle(SpanStyle(color = c.accentTeal)) { append("+") }; append(" made"); withStyle(SpanStyle(color = c.accentOrange)) { append("{}") } }, style = style, color = c.text)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(buildAnnotatedString { withStyle(SpanStyle(color = c.accentMagenta)) { append(">>>") }; append("clear") }, style = style, color = c.text)
            PhIcon("check-bold", Modifier.padding(start = 2.dp, bottom = (sizeSp * 0.05f).dp), tint = c.accentTeal, size = (sizeSp * 0.8f).dp)
        }
    }
}

@Composable
private fun Section(title: String, rows: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(vertical = 4.dp)) { rows() }
    }
}

@Composable
private fun ListRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).pressTint(onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** `RefAutocomplete`: free text with the repo's branches and tags suggested below. */
@Composable
private fun RefField(value: String, onChange: (String) -> Unit, suggestions: List<Pair<String, String>>, placeholder: String, icon: String?, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val shown = remember(value, suggestions) {
        val q = value.trim().lowercase()
        suggestions.filter { q.isEmpty() || it.first.lowercase().contains(q) || suggestions.any { s -> s.first == value } }
    }
    Box(modifier) {
        TextInput(
            value, { onChange(it); open = true }, placeholder,
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) open = true },
            icon = icon,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { open = false }),
        )
        PullsDropdown(focused && open && shown.isNotEmpty(), { open = false }) {
            Column(Modifier.heightIn(max = 288.dp)) {
                shown.forEach { (name, kind) ->
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(6.dp)).pressTint({ onChange(name); open = false }).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhIcon(kind, tint = c.fade, size = 16.dp)
                        Text(name, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
