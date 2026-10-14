package com.octaviantocan.pulls.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PendingDot
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.QuickAction
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import kotlin.math.abs

/**
 * A component as DESIGN.md sizes it, rendered by the kit with default parameters and no help. The
 * name is the DESIGN.md `components` token. `pnpm run android:design` fails when a sized token has
 * no specimen here and no reason in `book.exempt.json`.
 */
internal class Specimen(val name: String, val content: @Composable () -> Unit)

private val SPECIMENS = listOf(
    Specimen("float-button") { FloatIcon(Lu.cog, "Settings", {}) },
    Specimen("capsule-tabs") { CapsuleTabs(listOf("Inbox", "Tours", "Done"), 0, {}) },
    Specimen("soft-chip") { SoftChip("Needs review", false, {}) },
    Specimen("underline-tabs") { UnderlineTabs(listOf("Summary", "Description"), 0, {}) },
    Specimen("check") { PullsCheck(false) },
    Specimen("switch") { PullsSwitch(false, {}, "Auto refresh") },
    Specimen("cta-button") { CtaButton("Approve", {}) },
    Specimen("sheet-row") { SheetRow("Copy link", {}, first = true) },
    Specimen("quick-action") { QuickAction("Copy", {}, Modifier.fillMaxWidth()) { LuIcon(Lu.copy, size = 22.dp) } },
    Specimen("orb") { Orb() },
    Specimen("progress-ring") { ProgressRing(2, 5) },
    Specimen("breathing-dot") { BreathingDot() },
    Specimen("pending-dot") { PendingDot() },
    Specimen("ok-dot") { OkDot() },
    Specimen("skeleton") { Skeleton(Modifier.fillMaxWidth()) },
    Specimen("profile-dot") { ProfileDot() },
)

/** What a measured specimen says: `float-button ok`, or the side that is off and by how much. */
internal fun verdict(name: String, token: ComponentToken?, widthDp: Float, heightDp: Float): List<String> {
    if (token == null) return listOf("$name: DESIGN.md has no such component")
    val off = mutableListOf<String>()
    token.width?.let { if (abs(it - widthDp) > TOLERANCE) off += "$name.width: expected ${dp(it)}dp, rendered ${dp(widthDp)}dp" }
    token.height?.let { if (abs(it - heightDp) > TOLERANCE) off += "$name.height: expected ${dp(it)}dp, rendered ${dp(heightDp)}dp" }
    return off.ifEmpty { listOf("$name ok") }
}

private const val TOLERANCE = 0.5f

/**
 * The web book measures each specimen in a browser and compares it with DESIGN.md. Compose cannot
 * be measured from Node, so this page does it on the phone: every specimen is laid out, measured,
 * and compared with its token the moment the page opens.
 */
@Composable
internal fun SpecsPage(book: BookData) {
    val c = LocalPullsColors.current
    val results = remember { mutableStateOf(emptyMap<String, List<String>>()) }
    PageColumn {
        PageTitle("Component specs", "Each component is rendered by the kit and measured here against the size DESIGN.md gives it. A line that is not ok is a defect (rule 47).")
        val failing = results.value.values.count { it.firstOrNull()?.endsWith(" ok") == false }
        Text(
            if (results.value.size < SPECIMENS.size) "Measuring" else if (failing == 0) "${SPECIMENS.size} of ${SPECIMENS.size} ok" else "$failing of ${SPECIMENS.size} differ",
            style = PullsType.row.copy(fontWeight = PullsType.medium),
            color = if (failing == 0) c.success else c.error,
        )
        SPECIMENS.forEach { specimen ->
            Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                Measure(specimen, book.tokens[specimen.name]) { lines -> results.value = results.value + (specimen.name to lines) }
                (results.value[specimen.name] ?: emptyList()).forEach { line ->
                    Mono(line, muted = line.endsWith(" ok"))
                }
            }
        }
    }
}

@Composable
private fun Measure(specimen: Specimen, token: ComponentToken?, report: (List<String>) -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.onSizeChanged { measured ->
            report(verdict(specimen.name, token, measured.width / density.density, measured.height / density.density))
        },
    ) { specimen.content() }
}
