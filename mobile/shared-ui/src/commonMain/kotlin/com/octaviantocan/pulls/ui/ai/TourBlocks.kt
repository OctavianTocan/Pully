package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.review.ReviewSession

private val SHAPE_LABELS = mapOf("calls" to "Call tree", "tree" to "Files", "pseudo" to "Pseudocode", "flow" to "Flow")

/** Fence languages that name no language: the code is shown uncoloured. */
private val PLAIN_LANGS = setOf("text", "txt", "plain", "plaintext")


@Composable
fun TourBlockView(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    when (block.kind) {
        "text" -> Markdown(block.text, style = PullsType.read.copy(color = c.text))
        "shape" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The kind and its caption at body size: a 10sp label was below the floor of rule 14. Code carries no label:
            // it plainly is code, and its language ("text") said nothing ("why does it say Code Text?", recording 15 Oct
            // 15:37, 15:35 to 15:48; L30).
            if (block.lang != "code") BlockLabel(SHAPE_LABELS[block.lang] ?: "Sketch", block.text)
            // A flow is drawn from its hops, a call tree and a file tree as trees, pseudocode and code as the diff's code view (rule 95).
            AiFence(session, block.body, if (block.lang == "code") block.text.takeUnless { it.lowercase() in PLAIN_LANGS }.orEmpty() else block.lang)
        }
        "diagram" -> Diagram(session, block)
    }
}

