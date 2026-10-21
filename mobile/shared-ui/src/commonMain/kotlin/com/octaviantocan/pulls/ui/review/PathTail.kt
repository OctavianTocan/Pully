package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.octaviantocan.pulls.core.pathTail

/** [path] on one line, cut from the left at a folder so its end shows (rule 90), measured in [style] against the room it has. */
@Composable
fun PathTail(path: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val shown = remember(path, width, style) {
            if (width == Constraints.Infinity) path else pathTail(path) { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= width }
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}
