package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.octaviantocan.pulls.ui.review.ReviewSession

/**
 * A fenced block of AI text, by its language (rule 95): a ```flow is drawn as boxes and arrows, a ```calls as a call
 * tree and a ```tree as a file tree, both on guide lines, and anything else, pseudocode included, is the diff's code view.
 */
@Composable
fun AiFence(session: ReviewSession, code: String, lang: String) {
    when (lang.lowercase()) {
        "flow" -> {
            val items = remember(code) { parseFlow(code) }
            if (items != null) FlowView(items) else AiCodeView(session, code, "")
        }
        "pseudo", "pseudocode" -> PseudoView(session, code)
        // An answer in Ask draws with the tour's forms, a diagram too (L34).
        "mermaid" -> Diagram(session, com.octaviantocan.pulls.data.TourBlock("diagram", "", body = code))
        "calls" -> SketchTree(session, code, files = false)
        "tree" -> SketchTree(session, code, files = true)
        else -> AiCodeView(session, code, lang)
    }
}
