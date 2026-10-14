package com.octaviantocan.pulls.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PendingDot
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.QuickAction
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.UnderlineTabs

/** One state of a variant, forced: a label and the live component. */
internal class BookState(val label: String, val content: @Composable () -> Unit)

/** A variant is a tab on its component's page; [id] is the one `book.json` lists. */
internal class BookVariant(val id: String, val states: List<BookState>)

/**
 * The preview of each component in `book.json`, keyed by its id. `pnpm run android:design` checks
 * that every component has an arm, that the function it calls is in this file, and that its
 * `BookVariant` ids are the ones `book.json` lists.
 */
internal fun bookPreviews(id: String): List<BookVariant> = when (id) {
    "float-button" -> floatButtonVariants()
    "capsule-tabs" -> capsuleTabsVariants()
    "soft-chip" -> softChipVariants()
    "underline-tabs" -> underlineTabsVariants()
    "check" -> checkVariants()
    "switch" -> switchVariants()
    "cta-button" -> ctaButtonVariants()
    "pill-button" -> pillButtonVariants()
    "pill-input" -> pillInputVariants()
    "sheet" -> sheetVariants()
    "sheet-card" -> sheetCardVariants()
    "sheet-field" -> sheetFieldVariants()
    "sheet-row" -> sheetRowVariants()
    "quick-action" -> quickActionVariants()
    "toast" -> toastVariants()
    "orb" -> orbVariants()
    "progress-ring" -> progressRingVariants()
    "breathing-dot" -> breathingDotVariants()
    "pending-dot" -> pendingDotVariants()
    "ok-dot" -> okDotVariants()
    "skeleton" -> skeletonVariants()
    "profile-dot" -> profileDotVariants()
    else -> emptyList()
}

private fun floatButtonVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Rest") { FloatIcon(Lu.cog, "Settings", {}) },
            BookState("Disabled") { FloatIcon(Lu.cog, "Settings", {}, enabled = false) },
        ),
    ),
    BookVariant("flat", listOf(BookState("Flat") { FloatIcon(Lu.close, "Close", {}, flat = true) })),
)

@Composable
private fun DemoTabs(start: Int, flat: Boolean = false) {
    var selected by remember { mutableIntStateOf(start) }
    CapsuleTabs(listOf("Inbox", "Tours", "Done"), selected, { selected = it }, flat = flat)
}

private fun capsuleTabsVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("First selected") { DemoTabs(0) },
            BookState("Last selected") { DemoTabs(2) },
        ),
    ),
    BookVariant("flat", listOf(BookState("Flat") { DemoTabs(1, flat = true) })),
)

private fun softChipVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Unselected") { SoftChip("Needs review", false, {}) },
            BookState("Selected") { SoftChip("Needs review", true, {}) },
            BookState("With a count") { SoftChip("Needs review", true, {}, count = "4") },
        ),
    ),
)

@Composable
private fun DemoUnderline(start: Int) {
    var selected by remember { mutableIntStateOf(start) }
    UnderlineTabs(listOf("Summary", "Description"), selected, { selected = it })
}

private fun underlineTabsVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("First tab") { DemoUnderline(0) },
            BookState("Second tab") { DemoUnderline(1) },
        ),
    ),
)

@Composable
private fun DemoCheck() {
    var on by remember { mutableStateOf(false) }
    PullsCheck(on, onClick = { on = !on }, label = "Reviewed")
}

private fun checkVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Unchecked") { PullsCheck(false) },
            BookState("Checked") { PullsCheck(true) },
            BookState("Tap it") { DemoCheck() },
        ),
    ),
)

private fun switchVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Off") { PullsSwitch(false, {}, "Auto refresh") },
            BookState("On") { PullsSwitch(true, {}, "Auto refresh") },
            BookState("Disabled") { PullsSwitch(true, {}, "Auto refresh", enabled = false) },
        ),
    ),
)

private fun pillInputVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Empty") { PillInput("", {}, "Ask about this tour…") },
            BookState("Typed") { PillInput("Why is the cache keyed by SHA?", {}, "Ask about this tour…") },
        ),
    ),
    BookVariant(
        "with-send",
        listOf(
            BookState("Send") {
                PillInput("", {}, "Comment on line 12…") { SendButton({}, "Send") }
            },
        ),
    ),
)

private fun pillButtonVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Rest") { PillButton("Retry", {}) },
            BookState("Disabled") { PillButton("Retry", {}, enabled = false) },
        ),
    ),
    BookVariant(
        "with-icon",
        listOf(
            BookState("Leading icon") { PillButton("Rewrite", {}, leading = { LuIcon(Lu.redo, size = 20.dp) }) },
        ),
    ),
)

private fun ctaButtonVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Rest") { CtaButton("Approve", {}) },
            BookState("Disabled") { CtaButton("Approve", {}, enabled = false) },
        ),
    ),
    BookVariant(
        "with-icon",
        listOf(
            BookState("Leading icon") { CtaButton("Open diff", {}, leading = { LuIcon(Lu.diff, tint = LocalPullsColors.current.onCta, size = 20.dp) }) },
            BookState("Trailing icon") { CtaButton("Next file", {}, trailing = { LuIcon(Lu.arrow, tint = LocalPullsColors.current.onCta, size = 20.dp) }) },
        ),
    ),
)

/** The paper a sheet's cards sit on, so a card is seen on the surface it was made for. */
@Composable
private fun OnPaper(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.card)).background(LocalPullsColors.current.paper).padding(PullsSpace.x4)) { content() }
}

@Composable
private fun DemoRows() {
    val c = LocalPullsColors.current
    SheetCard {
        SheetRow("Copy link", {}, first = true, icon = { LuIcon(Lu.copy, tint = c.text, size = 20.dp) })
        SheetRow("Open on GitHub", {}, icon = { LuIcon(Lu.ext, tint = c.text, size = 20.dp) })
        SheetRow("Archive", {}, icon = { LuIcon(Lu.arch, tint = c.text, size = 20.dp) })
    }
}

/** The sheet is a window, so it cannot be framed inside a page: its surface is drawn here and the real one opens from the button. */
@Composable
private fun SheetSurface() {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(topStart = PullsRadius.sheet, topEnd = PullsRadius.sheet)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.paper).padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
        Text("Sheet title", style = PullsType.lg.copy(fontWeight = PullsType.semibold), color = c.text)
        DemoRows()
    }
}

@Composable
private fun SheetOpener() {
    var open by remember { mutableStateOf(false) }
    CtaButton("Open the sheet", { open = true })
    if (open) PullsSheet(onClose = { open = false }, title = "Sheet title") { DemoRows() }
}

/** Two pages in one sheet: the second slides in, the panel and the lip stay, and back finds the first where it was left. */
@Composable
private fun PagedSheetOpener() {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    CtaButton("Open the sheet with pages", { page = 0; open = true })
    if (open) {
        PullsPagedSheet(
            onClose = { open = false },
            page = page,
            parent = { if (it == 0) null else 0 },
            onPage = { page = it },
            title = { if (it == 0) "Settings" else "Language" },
        ) { current ->
            if (current == 0) {
                SheetCard {
                    SheetRow("Language", { page = 1 }, first = true, iconless = true)
                    SheetRow("Server", {}, iconless = true)
                }
            } else {
                SheetCard {
                    SheetRow("English", { page = 0 }, first = true, iconless = true)
                    SheetRow("Romanian", { page = 0 }, iconless = true)
                }
            }
        }
    }
}

private fun sheetVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Surface") { SheetSurface() },
            BookState("The real sheet") { SheetOpener() },
        ),
    ),
    BookVariant("pages", listOf(BookState("The real sheet") { PagedSheetOpener() })),
)

private fun sheetCardVariants() = listOf(
    BookVariant("default", listOf(BookState("Card of rows") { OnPaper { DemoRows() } })),
)

private fun sheetFieldVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Empty") { OnPaper { SheetCard { SheetField("Title", "", {}, placeholder = "Fix the cache key", first = true) } } },
            BookState("Filled, two fields") {
                OnPaper { SheetCard { SheetField("Repository", "OctavianTocan/pulls.review", {}, first = true); SheetField("Branch", "main", {}) } }
            },
            BookState("With a word") {
                OnPaper { SheetCard { SheetField("GitHub token", "ghp_example", {}, first = true, secret = true, trailing = { Text("Show", style = PullsType.row, color = LocalPullsColors.current.muted, modifier = Modifier.padding(horizontal = 8.dp)) }) } }
            },
        ),
    ),
    BookVariant(
        "note",
        listOf(BookState("Note area") { OnPaper { SheetCard { SheetField("Description", "", {}, placeholder = "What changed and why", first = true, minLines = 4) } } }),
    ),
    BookVariant(
        "secret",
        listOf(BookState("Hidden value") { OnPaper { SheetCard { SheetField("GitHub token", "ghp_example", {}, first = true, secret = true) } } }),
    ),
)

private fun sheetRowVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Icon and label") {
                OnPaper { SheetCard { SheetRow("Copy link", {}, first = true, icon = { LuIcon(Lu.copy, tint = LocalPullsColors.current.text, size = 20.dp) }) } }
            },
            BookState("With a value") {
                OnPaper {
                    SheetCard {
                        SheetRow(
                            "Branch",
                            {},
                            first = true,
                            icon = { LuIcon(Lu.merge, tint = LocalPullsColors.current.text, size = 20.dp) },
                            value = { Text("main", style = PullsType.sm, color = LocalPullsColors.current.muted) },
                        )
                    }
                }
            },
            BookState("Danger") {
                OnPaper { SheetCard { SheetRow("Close pull request", {}, first = true, danger = true, icon = { LuIcon(Lu.shut, tint = LocalPullsColors.current.error, size = 20.dp) }) } }
            },
            BookState("Disabled") {
                OnPaper { SheetCard { SheetRow("Merge", {}, first = true, enabled = false, icon = { LuIcon(Lu.merge, tint = LocalPullsColors.current.text, size = 20.dp) }) } }
            },
        ),
    ),
    BookVariant(
        "iconless",
        listOf(BookState("No icon column") { OnPaper { SheetCard { SheetRow("Unified", {}, first = true, iconless = true); SheetRow("Split", {}, iconless = true) } } }),
    ),
)

private fun quickActionVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Three tiles") {
                OnPaper {
                    Row(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                        QuickAction("Copy", {}, Modifier.weight(1f)) { LuIcon(Lu.copy, size = 22.dp) }
                        QuickAction("Reply", {}, Modifier.weight(1f)) { LuIcon(Lu.reply, size = 22.dp) }
                        QuickAction("Archive", {}, Modifier.weight(1f)) { LuIcon(Lu.arch, size = 22.dp) }
                    }
                }
            },
        ),
    ),
)

/** The toast is one host above the whole app, so these buttons show the real one at the bottom of the screen. */
private fun toastVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("With an action") {
                val toaster = LocalToaster.current
                CtaButton("Show the toast", { toaster.show("Marked as reviewed", "Undo", Cue.Success) {} })
            },
            BookState("Without an action") {
                val toaster = LocalToaster.current
                CtaButton("Show the toast", { toaster.show("Link copied", cue = Cue.Success) })
            },
        ),
    ),
)

private fun orbVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Working") { Orb() },
            BookState("Done") { Orb(done = true) },
        ),
    ),
)

private fun progressRingVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Not started") { ProgressRing(0, 5) },
            BookState("Two of five") { ProgressRing(2, 5) },
            BookState("Complete") { ProgressRing(5, 5) },
        ),
    ),
)

private fun breathingDotVariants() = listOf(BookVariant("default", listOf(BookState("Live") { BreathingDot() })))

private fun pendingDotVariants() = listOf(BookVariant("default", listOf(BookState("Waiting") { PendingDot() })))

private fun okDotVariants() = listOf(BookVariant("default", listOf(BookState("Finished") { OkDot() })))

private fun skeletonVariants() = listOf(
    BookVariant(
        "default",
        listOf(
            BookState("Two lines") {
                Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                    Skeleton(Modifier.fillMaxWidth())
                    Skeleton(Modifier.fillMaxWidth(0.6f))
                }
            },
            BookState("List rows") { RowsSkeleton(rows = 3) },
        ),
    ),
)

private fun profileDotVariants() = listOf(BookVariant("default", listOf(BookState("Placeholder") { ProfileDot() })))
