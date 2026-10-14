package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CodeFont
import com.octaviantocan.pulls.ui.design.CodeFonts
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard

/** A line of code that shows what a face does with brackets, zeros and the letters that get mistaken for each other. */
private const val SAMPLE = "review(0) { Il1 }"

/** [CodeFontPage] as a sheet of its own. Settings shows it as a page inside its sheet. */
@Composable
internal fun CodeFontSheet(settings: AppSettings, onClose: () -> Unit) {
    PullsSheet(onClose, "Code font") { CodeFontPage(settings, onClose) }
}

/** The faces code can be set in, each drawn in itself over a sample line. Picking one changes every code view at once, then runs [onDone]. */
@Composable
internal fun ColumnScope.CodeFontPage(settings: AppSettings, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    SheetCard {
        CodeFont.entries.forEachIndexed { index, font ->
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { if (index > 0) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                    .cueClickable(role = Role.RadioButton, onClickLabel = font.label) { settings.updateCodeFont(font.id); onDone() }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(font.label, style = PullsType.row, color = c.text, maxLines = 1)
                    Text(SAMPLE, style = PullsType.sm.copy(fontFamily = CodeFonts.familyOf(font)), color = c.muted, maxLines = 1)
                }
                Box(Modifier.padding(start = 4.dp)) { if (CodeFont.of(settings.codeFont) == font) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp) }
            }
        }
    }
}
