package com.octaviantocan.pulls.ui.settings

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
import com.octaviantocan.pulls.ui.design.AppFont
import com.octaviantocan.pulls.ui.design.AppFonts
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard

/** A line of the app's own words, so a face shows how a pull request's title and its numbers read in it. */
private const val SAMPLE = "Fix the cache key · 12 files"

/**
 * The faces the app's words can be set in, each name and sample drawn in itself, as the code font's page is (rule 81).
 * Picking one changes every screen at once, then runs [onDone] (N10).
 */
@Composable
internal fun ColumnScope.AppFontPage(settings: AppSettings, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    SheetCard {
        AppFont.entries.forEachIndexed { index, font ->
            val face = AppFonts.familyOf(font)
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { if (index > 0) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                    .cueClickable(role = Role.RadioButton, onClickLabel = font.label) { settings.updateAppFont(font.id); onDone() }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(font.label, style = PullsType.row.copy(fontFamily = face), color = c.text, maxLines = 1)
                    Text(SAMPLE, style = PullsType.sm.copy(fontFamily = face), color = c.muted, maxLines = 1)
                }
                Box(Modifier.padding(start = 4.dp)) { if (AppFont.of(settings.appFont) == font) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp) }
            }
        }
    }
}
