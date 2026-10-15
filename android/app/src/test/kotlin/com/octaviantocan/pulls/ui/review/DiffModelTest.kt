package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.FileNote
import com.octaviantocan.pulls.data.FullFile
import com.octaviantocan.pulls.data.Hunk
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.ui.design.ReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffModelTest {
    private fun file(path: String, sha: String = path, lines: List<DiffLine> = emptyList(), hunks: List<Hunk> = emptyList(), add: Int = 1, del: Int = 1) =
        ReviewFile(path = path, status = "modified", additions = add, deletions = del, sha = sha, patchLines = lines, hunks = hunks)

    // A 10-line file; one hunk @@ -4,3 +4,3 @@ changing line 5.
    private val hunk = Hunk("@@ -4,3 +4,3 @@", 4, 3, 4, 3)
    private val patch = listOf(
        DiffLine(' ', "l4", newLine = 4, oldLine = 4, gapBefore = 3),
        DiffLine('-', "old5", oldLine = 5),
        DiffLine('+', "new5", newLine = 5),
        DiffLine(' ', "l6", newLine = 6, oldLine = 6),
    )
    private val oldText = (1..10).joinToString("\n") { if (it == 5) "old5" else "l$it" } + "\n"
    private val newText = (1..10).joinToString("\n") { if (it == 5) "new5" else "l$it" } + "\n"
    private val tenLines = file("a.ts", lines = patch, hunks = listOf(hunk))

    // --- Gaps ---

    @Test
    fun patchGapsComeBeforeTheirHunk() {
        val entries = diffEntries(tenLines)
        assertEquals(DiffEntry.Gap(0, 3), entries.first())
        assertEquals(5, entries.size)
        assertTrue(entries.drop(1).all { it is DiffEntry.Line })
    }

    @Test
    fun fullFileShowsTheTrailingGapUntilOpened() {
        val entries = diffEntries(tenLines, FullFile(oldText, newText), emptySet())
        assertEquals(DiffEntry.Gap(0, 3), entries.first())
        assertEquals(DiffEntry.Gap(1, 4), entries.last())
    }

    @Test
    fun openingOneGapFillsItWithNumberedContext() {
        val entries = diffEntries(tenLines, FullFile(oldText, newText), setOf(0))
        val lines = entries.filterIsInstance<DiffEntry.Line>().map { it.line }
        assertEquals(listOf(1, 2, 3), lines.take(3).map { it.newLine })
        assertEquals(listOf(1, 2, 3), lines.take(3).map { it.oldLine })
        assertEquals("l1", lines.first().text)
        assertEquals(DiffEntry.Gap(1, 4), entries.last())
    }

    @Test
    fun loadingTheWholeFileOpensEveryGap() {
        val entries = diffEntries(tenLines, FullFile(oldText, newText), null)
        assertTrue(entries.none { it is DiffEntry.Gap })
        val lines = entries.filterIsInstance<DiffEntry.Line>().map { it.line }
        assertEquals((1..10).toList(), lines.mapNotNull { it.newLine })
        assertEquals((1..10).toList(), lines.mapNotNull { it.oldLine })
        assertEquals("l10", lines.last().text)
    }

    @Test
    fun aTooLargeFileIsDiffedFromItsFullContents() {
        val big = file("big.ts").copy(truncated = true)
        val lines = diffEntries(big, FullFile("a\nb\nc\n", "a\nB\nc\n"), null).map { (it as DiffEntry.Line).line }
        assertEquals(listOf(' ', '-', '+', ' '), lines.map { it.kind })
        assertEquals(listOf(1, 2, null, 3), lines.map { it.oldLine })
        assertEquals(listOf(1, null, 2, 3), lines.map { it.newLine })
    }

    // --- Split pairing with gaps ---

    @Test
    fun splitKeepsGapsFullWidthAndPairsChanges() {
        val split = splitEntries(diffEntries(tenLines))
        assertEquals(SplitEntry.Gap(DiffEntry.Gap(0, 3)), split[0])
        val pair = split[2] as SplitEntry.Pair
        assertEquals("old5", pair.left?.text)
        assertEquals("new5", pair.right?.text)
        assertEquals(4, split.size)
    }

    @Test
    fun splitIsForcedUnifiedWhenNarrowOrOneSided() {
        val both = file("x.ts", add = 2, del = 1)
        assertTrue(effectiveSplit("split", both, 900))
        assertFalse(effectiveSplit("split", both, 600))
        assertFalse(effectiveSplit("unified", both, 900))
        assertFalse(effectiveSplit("split", file("y.ts", add = 3, del = 0), 900))
    }

    // --- Noisy files ---

    @Test
    fun generatedAndLockFilesAreNoisy() {
        listOf("pnpm-lock.yaml", "packages/a/pnpm-lock.yaml", "yarn.lock", "package-lock.json", "Cargo.lock", "go.sum", "dist/index.js", "packages/cli/dist/a/b.mjs", "src/api.generated.ts", "poetry.lock")
            .forEach { assertTrue(isNoisyFile(it), it) }
        listOf("src/index.ts", "README.md", "distribution/a.ts", "package.json", "src/lockfile.ts", "go.mod")
            .forEach { assertFalse(isNoisyFile(it), it) }
    }

    @Test
    fun globsLikePicomatch() {
        assertTrue(globRegex("**/*.lock").matches("a/b/c.lock"))
        assertTrue(globRegex("**/*.lock").matches("c.lock"))
        assertFalse(globRegex("**/*.lock").matches("a/.hidden/c.lock"))
        assertTrue(globRegex("**/dist/**").matches("dist/x"))
        assertTrue(globRegex("**/dist/**").matches("dist"))
        assertFalse(globRegex("**/*.lock").matches(".c.lock"))
    }

    // --- Tree ---

    @Test
    fun singleChildFolderChainsCollapseIntoOneRow() {
        val rows = treeRows(listOf(file("src/components/diff/A.vue"), file("src/components/diff/B.vue"), file("README.md")))
        assertEquals(listOf("README.md", "src/components/diff", "A.vue", "B.vue"), rows.map { it.name })
        assertEquals(listOf(0, 0, 1, 1), rows.map { it.depth })
        assertEquals(TreeRowType.Folder, rows[1].type)
        assertEquals(2, rows[1].files.size)
    }

    @Test
    fun foldersStopCollapsingWhereTheyBranch() {
        val rows = treeRows(listOf(file("src/a/x.ts"), file("src/b/y.ts")))
        assertEquals(listOf("src" to 0, "a" to 1, "x.ts" to 2, "b" to 1, "y.ts" to 2), rows.map { it.name to it.depth })
        assertEquals(2, rows[0].files.size)
    }

    @Test
    fun missingPathsAreRemovedLeaves() {
        val rows = treeRows(listOf(file("src/a.ts")), missing = listOf("src/gone.ts"))
        val missing = rows.single { it.type == TreeRowType.Missing }
        assertEquals("gone.ts", missing.name)
        assertEquals(1, missing.depth)
        assertEquals(1, rows.single { it.type == TreeRowType.Folder }.files.size)
    }

    @Test
    fun folderReviewStateIsPartialWhenSomeFilesAre() {
        val files = listOf(file("a", "s1"), file("b", "s2"))
        assertEquals(ReviewState.Unreviewed, folderState(files, emptySet()))
        assertEquals(ReviewState.Partial, folderState(files, setOf("s1")))
        assertEquals(ReviewState.Reviewed, folderState(files, setOf("s1", "s2", "other")))
    }

    // --- Anchoring ---

    private fun thread(line: Int?, side: String = "additions", path: String = "a.ts", outdated: Boolean = false, resolved: Boolean = false, pending: Boolean = false) =
        ReviewThread(1, path, side, line, null, outdated, resolved, "T", pending, emptyList())

    @Test
    fun threadsAnchorToTheirSideAndLine() {
        val rows = diffEntries(tenLines).map { e -> if (e is DiffEntry.Line) listOf(e.line) else emptyList() }
        val placed = placeAnchors(rows, listOf(Anchor("additions", 5), Anchor("deletions", 5), Anchor("deletions", 6), Anchor("additions", 99)))
        assertEquals(3, placed[Anchor("additions", 5)])
        assertEquals(2, placed[Anchor("deletions", 5)])
        // A context line answers on both sides.
        assertEquals(4, placed[Anchor("deletions", 6)])
        assertNull(placed[Anchor("additions", 99)])
    }

    @Test
    fun splitRowsAnchorBothColumns() {
        val rows = splitEntries(diffEntries(tenLines)).map { e -> if (e is SplitEntry.Pair) listOfNotNull(e.left, e.right) else emptyList() }
        val placed = placeAnchors(rows, listOf(Anchor("additions", 5), Anchor("deletions", 5)))
        assertEquals(2, placed[Anchor("additions", 5)])
        assertEquals(2, placed[Anchor("deletions", 5)])
    }

    @Test
    fun visibleThreadsHideOutdatedResolvedAndHiddenOnes() {
        val all = listOf(thread(5), thread(null), thread(5, outdated = true), thread(5, resolved = true), thread(6, pending = true), thread(5, path = "b.ts"))
        assertEquals(2, visibleThreads(all, "a.ts", showThreads = true).size)
        assertEquals(3, visibleThreads(all, "a.ts", showThreads = true, includeResolved = true).size)
        // Hidden threads still show the viewer's own pending drafts.
        assertEquals(listOf(6), visibleThreads(all, "a.ts", showThreads = false).map { it.line })
    }

    @Test
    fun hunkNotesSitUnderTheLastContextLineBeforeTheChange() {
        assertEquals(Anchor("additions", 4), hunkAnchor(patch))
        assertEquals(Anchor("deletions", 5), hunkAnchor(patch.drop(1)))
        assertEquals(Anchor("additions", 5), hunkAnchor(patch.drop(2)))
        assertNull(hunkAnchor(emptyList()))
    }

    @Test
    fun aiAnchorsGroupFindingsNotesAndAsksByLine() {
        val finding = CritiqueFinding("f1", "a.ts", "additions", 5, null, "bug", "Title", "Body", null)
        val other = finding.copy(id = "f2", path = "b.ts")
        val ask = AskThread("q1", "a.ts", "additions", 4, 5, "a.ts:L4-L5", emptyList())
        val anchors = aiAnchors(tenLines, listOf(finding, other), FileNote("a.ts", "sum", mapOf(0 to "note")), listOf(ask))
        assertEquals(listOf(finding), anchors.getValue(Anchor("additions", 5)).findings)
        assertEquals(listOf(ask), anchors.getValue(Anchor("additions", 5)).asks)
        assertEquals(listOf("note"), anchors.getValue(Anchor("additions", 4)).notes)
        assertEquals(2, anchors.size)
    }

    // --- Ranges and time ---

    @Test
    fun rangesNormalizeTheirEnds() {
        assertEquals(DraftTarget("a", "additions", 9, 3), selectRange("a", "additions", 9, 3))
        assertEquals(DraftTarget("a", "additions", 4, null), selectRange("a", "additions", 4, 4))
    }

    @Test
    fun selectedLinesCarryTheirCodeOnTheirSide() {
        val entries = diffEntries(tenLines)
        // Lines 4 to 5 of the new side: the context line and the added one, not the removed one.
        assertEquals("l4\nnew5", selectedCode(entries, "additions", 4..5))
        // Line 5 of the old side is the removed line.
        assertEquals("old5", selectedCode(entries, "deletions", 5..5))
        assertEquals("", selectedCode(entries, "additions", 40..41))
    }

    @Test
    fun linesTheDiffHasNotLoadedAreMarkedInTheSelectedCode() {
        // Line 5 is not on screen, as if it sat in a folded gap.
        val holed = diffEntries(tenLines).filterNot { (it as? DiffEntry.Line)?.line?.newLine == 5 }
        assertEquals("l4\n…\nl6", selectedCode(holed, "additions", 4..6))
    }

    @Test
    fun timeAgoMatchesTheWebFormat() {
        val now = 1_000_000_000_000L
        assertEquals("just now", timeAgo(now, now))
        assertEquals("3 d ago", timeAgo(now - 3 * 86_400_000L, now))
        assertEquals("5 min ago", timeAgo(now - 5 * 60_000L, now))
        assertEquals("in 2 h", timeAgo(now + 2 * 3_600_000L, now))
    }
}
