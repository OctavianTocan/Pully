package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.PaletteScope
import com.octaviantocan.pulls.ui.UseCommands
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.home.NativeHomeActions

/**
 * Port of `usePrCommands.ts` for the palette: step through the files, mark the current one, copy or
 * open its links, jump to any file, analyze, and the view toggles. There is no refresh: the page takes
 * new commits by itself. [currentPath] is the file the diff page shows (the first one when none is open
 * yet); [onGo] opens the diff on a file. [files] gives the files in reading order when a command needs them, so the
 * page does not order a large pull request's files on every frame (N22). [onDiff] is true while the Diff is on screen:
 * stepping through the files and marking one reviewed are then its Suggested commands (N14).
 */
@Composable
fun PrCommands(session: ReviewSession, files: () -> List<ReviewFile>, currentPath: String?, onGo: (String) -> Unit, onAnalyze: () -> Unit, native: NativeHomeActions, onDiff: Boolean = false) {
    val actions = rememberReviewCommandActions(session)
    val m = LocalMessages.current

    UseCommands {
        val opened = session.opened ?: return@UseCommands emptyList()
        if (!actions.current) return@UseCommands emptyList()
        val readingOrder = files()
        val current = readingOrder.firstOrNull { it.path == currentPath } ?: readingOrder.firstOrNull()
        fun step(direction: Int) {
            val file = current ?: return
            val at = readingOrder.indexOfFirst { it === file }
            readingOrder.getOrNull(at + direction)?.let { next -> actions.file(file) { actions.file(next) { onGo(next.path) } } }
        }
        val pr = m.t("palette.group.pr")
        val view = m.t("palette.group.view")
        val repo = FileLinks.repoOf(session.target)
        val github = repo != null && opened.refKind.startsWith("github")
        val list = mutableListOf<Command>()
        if (actions.canDiff) list += listOfNotNull(
            Command("pr.nextFile", m.t("keys.nextFile"), pr, suggested = onDiff) { step(1) },
            Command("pr.prevFile", m.t("keys.prevFile"), pr, suggested = onDiff) { step(-1) },
            if (actions.canMarkReviewed) Command("pr.toggleReviewed", m.t("keys.toggleReviewed"), pr, suggested = onDiff) {
                current?.let(actions::toggleReviewed)
            } else null,
            Command("pr.copyPath", m.t("keys.copyPath"), pr) { current?.let { file -> actions.file(file) { native.copyText(file.path) } } },
            Command("pr.findFile", m.t("keys.findFile"), pr, suggested = onDiff) {
                actions.diff { Commands.open(PaletteScope("jump.file:", m.t("palette.group.files"))) }
            },
            Command("pr.layout", m.t("keys.layout"), view) {
                actions.diff { session.settings.updateLayout(if (session.settings.layout == "split") "unified" else "split") }
            },
            Command("pr.zoomIn", "Larger code", view, keywords = "zoom font size bigger") { actions.diff { session.settings.zoomBy(1) } },
            Command("pr.zoomOut", "Smaller code", view, keywords = "zoom font size") { actions.diff { session.settings.zoomBy(-1) } },
            Command("pr.zoomReset", "Reset code size", view, keywords = "zoom font size 100") { actions.diff { session.settings.updateCodeZoom(1f) } },
            Command("pr.wrap", if (session.settings.codeWrap) "Scroll long code lines" else "Wrap long code lines", view, keywords = "wrap soft") {
                actions.diff { session.settings.updateCodeWrap(!session.settings.codeWrap) }
            },
        )
        if (github && actions.canDiff) {
            list += Command("pr.openPr", m.t("keys.openPr"), pr) { actions.diff { native.openOnGithub(opened.url) } }
            list += Command("pr.openFile", m.t("keys.openFile"), pr) {
                val f = current ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                actions.file(f) { native.openOnGithub(FileLinks.blob(repo!!.first, repo.second, sha, f.path)) }
            }
            list += Command("pr.copyPermalink", m.t("keys.copyPermalink"), pr) {
                val f = current ?: return@Command
                val sha = if (f.status == "removed") opened.baseSha else opened.headSha
                actions.file(f) { native.copyText(FileLinks.blob(repo!!.first, repo.second, sha, f.path)) }
            }
        }
        if (actions.canDiff && session.reviewData != null) list += Command("pr.threads", m.t("keys.threads"), view) {
            actions.diff { session.settings.updateShowThreads(!session.settings.showThreads) }
        }
        if (actions.canReadAi && session.hasAiResult) list += Command("pr.analyzeMode", m.t("keys.analyzeMode"), view) {
            actions.ai { session.analyzeMode = if (session.analyzeMode == "rules") "ai" else "rules" }
        }
        if (actions.canAnalyze) list += Command("pr.analyze", "Analyze changes", pr, keywords = "analyze summary file notes tour") { actions.analyze(onAnalyze) }
        // `jump.file:` entries.
        val group = m.t("palette.group.files")
        if (actions.canDiff) session.files.forEach { f ->
            list += Command("jump.file:${f.sha}:${f.path}", m.t("palette.jumpToFile", "path" to f.path), group, keywords = f.previousPath) {
                actions.file(f) { session.focus = Focus(f.path) }
            }
        }
        list
    }
}
