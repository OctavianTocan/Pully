package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.ui.review.ReviewSession

/** Only this drawn thread owns the private, unsent follow-up consumed by the actual PillInput. */
@Stable
internal class AskComposerState {
    var question by mutableStateOf("")
}

@Composable
internal fun rememberAskComposerState(session: ReviewSession, owner: ReviewSession.Version?, threadId: String): AskComposerState =
    remember(session, owner, threadId) { AskComposerState() }
