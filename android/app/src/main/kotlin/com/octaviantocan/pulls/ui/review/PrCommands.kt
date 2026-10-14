package com.octaviantocan.pulls.ui.review

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.PaletteScope
import com.octaviantocan.pulls.ui.UseCommands
import com.octaviantocan.pulls.ui.design.LocalMessages

/**
 * Port of `usePrCommands.ts` for the palette: step through the files, mark the current one, copy or
 * open its links, jump to any file, refresh, analyze, and the view toggles. [currentPath] is the file
 * the diff page shows (the first one when none is open yet); [onGo] opens the diff on a file.
 */
@Composable
fun PrCommands(session: ReviewSession, files: List<ReviewFile>, currentPath: String?, onGo: (String) -> Unit, onAnalyze: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMessages.current

    fun current(): ReviewFile? = files.firstOrNull { it.path == currentPath } ?: files.firstOrNull()

    fun step(direction: Int) {
        val at = files.indexOfFirst { it.path == current()?.path }
        files.getOrNull(at + direction)?.let { onGo(it.path) }
    }

    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    UseCommands {
        val opened = session.opened ?: return@UseCommands emptyList()
        val pr = m.t("palette.group.pr")
        val view = m.t("palette.group.view")
        val repo = FileLinks.repoOf(session.target)
        val github = repo != null && opened.refKind.startsWith("github")
        val list = mutableListOf(
            Command("pr.nextFile", m.t("keys.nextFile"), pr) { step(1) },
            Command("pr.prevFile", m.t("keys.prevFile"), pr) { step(-1) },
            Command("pr.toggleReviewed", m.t("keys.toggleReviewed"), pr) {
                current()?.let { session.setReviewed(listOf(it.sha), it.sha !in session.reviewed) }
            },
            Command("pr.copyPath", m.t("keys.copyPath"), pr) { current()?.let { copyText(context, it.path) } },
            Command("pr.findFile", m.t("keys.findFile"), pr) {
                Commands.open(PaletteScope("jump.file:", m.t("palette.group.files")))
            },
            Command("pr.refresh", m.t("keys.refresh"), pr) { session.refresh() },
            Command("pr.layout", m.t("keys.layout"), view) {
                session.settings.updateLayout(if (session.settings.layout == "split") "unified" else "split")
            },
            Command("pr.zoomIn", "Larger code", view, keywords = "zoom font size bigger") { session.settings.zoomBy(1) },
            Command("pr.zoomOut", "Smaller code", view, keywords = "zoom font size") { session.settings.zoomBy(-1) },
            Command("pr.zoomReset", "Reset code size", view, keywords = "zoom font size 100") { session.settings.updateCodeZoom(1f) },
            Command("pr.wrap", if (session.settings.codeWrap) "Scroll long code lines" else "Wrap long code lines", view, keywords = "wrap soft") {
                session.settings.updateCodeWrap(!session.settings.codeWrap)
            },
        )
        if (github) {
            list += Command("pr.openPr", m.t("keys.openPr"), pr) { open(opened.url) }
            list += Command("pr.openFile", m.t("keys.openFile"), pr) {
                val f = current() ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                open(FileLinks.blob(repo!!.first, repo.second, sha, f.path))
            }
            list += Command("pr.copyPermalink", m.t("keys.copyPermalink"), pr) {
                val f = current() ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                copyText(context, FileLinks.blob(repo!!.first, repo.second, sha, f.path))
            }
        }
        if (session.reviewData != null) list += Command("pr.threads", m.t("keys.threads"), view) {
            session.settings.updateShowThreads(!session.settings.showThreads)
        }
        if (session.hasAiResult) list += Command("pr.analyzeMode", m.t("keys.analyzeMode"), view) {
            session.analyzeMode = if (session.analyzeMode == "rules") "ai" else "rules"
        }
        list += Command("pr.analyze", m.t("keys.analyze"), pr) {
            if (session.hasAiResult && session.analyzeMode == "rules") session.analyzeMode = "ai" else onAnalyze()
        }
        // `jump.file:` entries.
        val group = m.t("palette.group.files")
        session.files.forEach { f ->
            list += Command("jump.file:${f.sha}:${f.path}", m.t("palette.jumpToFile", "path" to f.path), group, keywords = f.previousPath) {
                session.focus = Focus(f.path)
            }
        }
        list
    }
}
