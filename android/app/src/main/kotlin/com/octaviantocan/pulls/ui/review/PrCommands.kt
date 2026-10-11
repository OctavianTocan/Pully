package com.octaviantocan.pulls.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.PaletteScope
import com.octaviantocan.pulls.ui.UseCommands
import com.octaviantocan.pulls.ui.design.LocalMessages
import kotlinx.coroutines.launch

/**
 * Port of `usePrCommands.ts` for the palette: step files and groups, mark the current file,
 * fold it, open or copy its GitHub links, jump to any file or group, refresh, analyze, and the
 * view toggles. "Current file" is the topmost file whose rows are on screen.
 */
@Composable
fun PrCommands(session: ReviewSession, ui: DiffUi, listState: LazyListState, onAnalyze: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMessages.current
    val scope = rememberCoroutineScope()

    fun bodyStart() = listState.layoutInfo.totalItemsCount - ui.bodyCount

    /** Paths in display order, with the global index of each file header. */
    fun fileHeaders(): List<Pair<String, Int>> =
        ui.indexOf.filterKeys { it.startsWith("diff:fh:") }.map { (k, v) -> k.removePrefix("diff:fh:") to (bodyStart() + v) }.sortedBy { it.second }

    fun groupHeaders(): List<Pair<String, Int>> =
        ui.indexOf.filterKeys { it.startsWith("diff:g:") }.map { (k, v) -> k.removePrefix("diff:g:") to (bodyStart() + v) }.sortedBy { it.second }

    fun currentFile(): String? {
        val first = listState.firstVisibleItemIndex
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.let { ui.pathOfKey[it] } }
        return visible.firstOrNull() ?: fileHeaders().lastOrNull { it.second <= first }?.first
    }

    fun step(list: List<Pair<String, Int>>, direction: Int) {
        val first = listState.firstVisibleItemIndex
        val target = if (direction > 0) list.firstOrNull { it.second > first } else list.lastOrNull { it.second < first }
        target?.let { scope.launch { listState.animateScrollToItem(it.second) } }
    }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
    }

    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    UseCommands {
        val opened = session.opened ?: return@UseCommands emptyList()
        val pr = m.t("palette.group.pr")
        val view = m.t("palette.group.view")
        val repo = FileLinks.repoOf(session.target)
        val github = repo != null && opened.refKind.startsWith("github")
        val file = { currentFile()?.let { path -> session.files.firstOrNull { it.path == path } } }
        val list = mutableListOf(
            Command("pr.nextFile", m.t("keys.nextFile"), pr, icon = "arrow-down") { step(fileHeaders(), 1) },
            Command("pr.prevFile", m.t("keys.prevFile"), pr, icon = "arrow-up") { step(fileHeaders(), -1) },
            Command("pr.nextGroup", m.t("keys.nextGroup"), pr, icon = "caret-double-down") { step(groupHeaders(), 1) },
            Command("pr.prevGroup", m.t("keys.prevGroup"), pr, icon = "caret-double-up") { step(groupHeaders(), -1) },
            Command("pr.toggleReviewed", m.t("keys.toggleReviewed"), pr, icon = "check-square-duotone") {
                file()?.let { session.setReviewed(listOf(it.sha), it.sha !in session.reviewed) }
            },
            Command("pr.toggleFile", m.t("keys.toggleFile"), pr, icon = "caret-up-down") {
                file()?.let { ui.setFileCollapsed(it.path, !ui.isFileCollapsed(it)) }
            },
            Command("pr.copyPath", m.t("keys.copyPath"), pr, icon = "copy-duotone") { file()?.let { copy(it.path) } },
            Command("pr.findFile", m.t("keys.findFile"), pr, icon = "magnifying-glass-duotone") {
                Commands.open(PaletteScope("jump.file:", m.t("palette.group.files")))
            },
            Command("pr.refresh", m.t("keys.refresh"), pr, icon = "arrows-clockwise-duotone") { session.refresh() },
            Command("pr.layout", m.t("keys.layout"), view, icon = "columns-duotone") {
                session.settings.updateLayout(if (session.settings.layout == "split") "unified" else "split")
            },
        )
        if (github) {
            list += Command("pr.openPr", m.t("keys.openPr"), pr, icon = "arrow-square-out-duotone") { open(opened.url) }
            list += Command("pr.openFile", m.t("keys.openFile"), pr, icon = "file-arrow-up-duotone") {
                val f = file() ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                open(FileLinks.blob(repo!!.first, repo.second, sha, f.path))
            }
            list += Command("pr.copyPermalink", m.t("keys.copyPermalink"), pr, icon = "link-duotone") {
                val f = file() ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                copy(FileLinks.blob(repo!!.first, repo.second, sha, f.path))
            }
        }
        if (session.reviewData != null) list += Command("pr.threads", m.t("keys.threads"), view, icon = "chats-duotone") {
            session.settings.updateShowThreads(!session.settings.showThreads)
        }
        if (session.hasAiResult) list += Command("pr.analyzeMode", m.t("keys.analyzeMode"), view, icon = "shuffle-duotone") {
            session.analyzeMode = if (session.analyzeMode == "rules") "ai" else "rules"
        }
        list += Command("pr.analyze", m.t("keys.analyze"), pr, icon = "sparkle-duotone") {
            if (session.hasAiResult && session.analyzeMode == "rules") session.analyzeMode = "ai" else onAnalyze()
        }
        // `jump.file:` and `jump.group:` entries.
        val files = m.t("palette.group.files")
        session.files.forEach { f ->
            list += Command("jump.file:${f.sha}:${f.path}", m.t("palette.jumpToFile", "path" to f.path), files, keywords = f.previousPath, icon = "file-duotone") {
                session.focus = Focus(f.path)
            }
        }
        val groups = m.t("palette.group.groups")
        session.groups.forEach { g ->
            val label = if (g.key == "uncategorized" && g.label.isBlank()) m.t("group.uncategorized") else g.label
            list += Command("jump.group:${g.key}", m.t("palette.jumpToGroup", "name" to label), groups, icon = "folder-simple-duotone") { session.focusGroupKey = g.key }
            g.children.forEach { child ->
                list += Command("jump.group:${g.key}/${child.key}", m.t("palette.jumpToGroup", "name" to "$label › ${child.label}"), groups, icon = "folder-simple-duotone") {
                    session.focusGroupKey = "${g.key}/${child.key}"
                }
            }
        }
        list
    }
}
