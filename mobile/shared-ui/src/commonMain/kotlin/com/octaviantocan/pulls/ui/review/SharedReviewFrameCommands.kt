package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.UseCommands
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.PullToCommands

/** Both hosts install the original review palette registrations and pull gesture over their one app palette. */
class SharedReviewFrameCommands(private val native: NativeHomeActions, private val current: () -> Boolean) : ReviewFrameCommands {
    @Composable
    override fun Effect(
        session: ReviewSession, files: () -> List<ReviewFile>, path: String?, onGo: (String) -> Unit,
        onAnalyze: () -> Unit, onDiff: Boolean, head: PrHead?, onOverview: Boolean, onReviewFiles: () -> Unit,
        onAsk: () -> Unit, onReview: (String) -> Unit,
    ) {
        PrCommands(session, files, path, onGo, onAnalyze, native, onDiff)
        val messages = LocalMessages.current
        val actions = rememberReviewCommandActions(session)
        UseCommands {
            if (!current() || !onOverview || head == null || !actions.current) return@UseCommands emptyList()
            val group = messages.t("palette.group.pr")
            buildList {
                if (actions.canDiff) add(Command("pr.reviewFiles", "Review files", group, keywords = "diff changes code", suggested = true) { actions.diff(onReviewFiles) })
                if (actions.canAsk) add(Command("pr.ask", "Ask about this pull request", group, keywords = "question chat ai", suggested = true) { actions.ask(onAsk) })
                if (actions.canSubmit) add(Command("pr.leaveComment", "Leave a comment", group, keywords = "review comment", suggested = true) { actions.submit(false) { onReview("COMMENT") } })
                if (actions.canVerdict) add(Command("pr.approve", "Approve", group, keywords = "review approve lgtm", suggested = true) { actions.submit(true) { onReview("APPROVE") } })
            }
        }
    }

    @Composable
    override fun Pull(modifier: Modifier, content: @Composable () -> Unit) {
        val lifetime = remember { PullLifetime() }
        DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
        PullToCommands({ if (lifetime.active && current()) Commands.open() }, modifier, content)
    }

    private class PullLifetime { var active = true }
}
