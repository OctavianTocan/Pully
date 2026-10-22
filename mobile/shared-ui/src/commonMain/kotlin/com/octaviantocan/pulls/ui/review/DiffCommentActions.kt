package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** The original pending-review comment action, bound to the exact file and host which drew its field. */
class DiffCommentActions(
    private val session: ReviewSession,
    private val ui: DiffUi,
    private val file: ReviewFile,
    scope: CoroutineScope,
    private val capabilities: () -> ReviewCapabilities,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val feedback: (Cue, String?) -> Unit,
) {
    private val owner = ui.captureFileRead(file)
    private val actions = ThreadActions(scope, { if (current()) session.reloadReviews() }, { cue, _ -> notify(cue) })
    val busy: Boolean get() = actions.busy
    val error: String? get() = actions.error

    private fun ownsReply(): Boolean = readiness.installedReview(capabilities()).canWriteComments && session.canWrite && !file.isBinary && ui.isCurrentFileRead(owner, file)
    private fun current(): Boolean = ownsReply() && readiness.review(capabilities()).canWriteComments
    private fun requireOwner() {
        if (!ownsReply()) throw CancellationException("The drawn comment field has retired.")
    }
    private fun notify(cue: Cue) {
        if (current()) feedback(cue, if (cue == Cue.Error) actions.error else null)
    }

    fun post(draft: DraftTarget, text: String, after: () -> Unit = {}) {
        val body = text.trim()
        if (!current() || body.isEmpty() || draft.path != file.path || ui.drafts[file.path] !== draft) return
        val pull = session.pull ?: return
        val head = owner?.owner?.head ?: return
        actions.run({
            if (!current()) throw CancellationException("The drawn comment field is unavailable.")
            try {
                session.repo.comment(pull, draft.path, draft.side, draft.line, head, body, "review", draft.startLine?.takeIf { it < draft.line })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                requireOwner()
                throw failure
            }
            requireOwner()
        }) { if (ownsReply() && ui.drafts[file.path] === draft) after() }
    }
}
