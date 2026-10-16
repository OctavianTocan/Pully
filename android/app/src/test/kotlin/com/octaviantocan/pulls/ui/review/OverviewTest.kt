package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.DETAILS_INFO

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `pr-overview` parsing against a real server answer (antfu/pulls.review#106). */
class OverviewParsingTest {
    private val fixture = javaClass.getResource("/fixtures/pr-overview-106.json")!!.readText()
    private val data = parsePrOverviewData(Json.parseToJsonElement(fixture))!!
    private val o = data.overview

    @Test
    fun readsThePullRequest() {
        assertEquals(106, o.number)
        assertEquals("open", o.state)
        assertEquals("PR_kwDOUcx-yc8AAAABHkAYhw", o.id)
        assertEquals("fix/keep-collapsed-file-in-view", o.headRef)
        assertEquals("AlsoKnownAs-Ax", o.headOwner)
        assertEquals("main", o.baseRef)
        assertEquals("b3cb89348cc660022ab56f34b71830e88f745fe7", o.headOid)
        assertEquals("AlsoKnownAs-Ax", o.author?.login)
        assertTrue(o.bodyHTML.startsWith("<h2"))
    }

    @Test
    fun readsChecksAndSummary() {
        assertEquals(PrChecksSummary(failing = 1, pending = 0, passing = 1, skipped = 1, total = 3, hidden = 0, state = "failure"), o.checksSummary)
        assertEquals(3, o.checks.size)
        assertEquals("failing", o.checks.first().bucket)
        assertEquals("Vercel", o.checks.first().name)
    }

    @Test
    fun readsCommitsAndTimeline() {
        assertEquals(1, o.commits.size)
        assertEquals("b3cb893", o.commits[0].abbreviatedOid)
        assertEquals("failure", o.commits[0].checks)
        assertEquals("AlsoKnownAs-Ax", o.commits[0].authorLogin)
        val kinds = o.timeline.map { it::class.simpleName }
        assertTrue("Commits" in kinds && "Comment" in kinds && "Review" in kinds && "Event" in kinds, kinds.toString())
        val review = o.timeline.filterIsInstance<PrTimelineItem.Review>().first()
        assertEquals("commented", review.state)
        assertEquals(1, review.commentCount)
        assertEquals("packages/app/src/components/diff/FileDiff.test.ts", review.comments[0].path)
        assertEquals(117, review.comments[0].line)
        val event = o.timeline.filterIsInstance<PrTimelineItem.Event>().first()
        assertEquals("ready_for_review", event.event)
        assertEquals(o.timeline.count { it is PrTimelineItem.Comment || it is PrTimelineItem.Review }, o.conversationCount)
    }

    @Test
    fun readsViewerMergeAndDefaults() {
        assertFalse(o.viewer.canPush)
        assertEquals("READ", o.viewer.permission)
        assertEquals(listOf("squash"), o.merge.methods)
        assertEquals("squash", o.merge.defaultMethod)
        assertEquals(PrMergeability(allowed = false, ready = false), data.mergeability)
        assertEquals("fix(app): keep a file's header in view when it collapses (#106)" to "", data.defaultCommits["squash"])
        assertEquals("Merge pull request #106 from AlsoKnownAs-Ax/fix/keep-collapsed-file-in-view", data.defaultCommits["merge"]?.first)
        assertEquals(PrMergeability(false, false), assessMergeability(o))
    }

    @Test
    fun rejectsMissingOverview() {
        assertNull(parsePrOverviewData(Json.parseToJsonElement("""{"mergeability":{}}""")))
        assertNull(parsePrOverviewData(null))
    }

    @Test
    fun readsEventSlots() {
        val json = """{"overview":{"id":"x","number":1,"state":"closed","merge":{"methods":["merge","rebase"],"defaultMethod":"squash"},
            "timeline":[{"kind":"event","id":"e","createdAt":"2026-01-01T00:00:00Z","event":"labeled","label":{"name":"bug","color":"d73a4a"}},
                        {"kind":"event","id":"f","createdAt":"2026-01-01T00:00:00Z","event":"renamed","from":"Old","to":"New"},
                        {"kind":"comment","id":"g","createdAt":"2026-01-01T00:00:00Z","url":"u","bodyHTML":"<p>x</p>","minimizedReason":"outdated"}]}}"""
        val parsed = parsePrOverviewData(Json.parseToJsonElement(json))!!.overview
        val labeled = parsed.timeline[0] as PrTimelineItem.Event
        assertEquals("bug", labeled.labelName)
        assertEquals("d73a4a", labeled.labelColor)
        val renamed = parsed.timeline[1] as PrTimelineItem.Event
        assertEquals("Old" to "New", renamed.from to renamed.to)
        assertEquals("outdated", (parsed.timeline[2] as PrTimelineItem.Comment).minimizedReason)
        // An unknown default method falls back to the first allowed one, like `mergeSettings`.
        assertEquals("merge", parsed.merge.defaultMethod)
    }
}

/** Port of `assessMergeability` and the merge box's state actions. */
class MergeabilityTest {
    private val base = parsePrOverviewData(Json.parseToJsonElement(javaClass.getResource("/fixtures/pr-overview-106.json")!!.readText()))!!.overview
    private val writer = base.copy(viewer = base.viewer.copy(canPush = true, canClose = true, canUpdate = true), mergeable = "mergeable", mergeStateStatus = "clean")

    @Test
    fun cleanIsReady() = assertEquals(PrMergeability(true, true), assessMergeability(writer))

    @Test
    fun withoutPushItIsNotAllowed() = assertEquals(PrMergeability(false, false), assessMergeability(base))

    @Test
    fun draftConflictsAndChecking() {
        assertEquals(PrMergeability(true, false, "draft"), assessMergeability(writer.copy(state = "draft")))
        assertEquals(PrMergeability(true, false, "conflicts"), assessMergeability(writer.copy(mergeable = "conflicting")))
        assertEquals(PrMergeability(true, false, "conflicts"), assessMergeability(writer.copy(mergeStateStatus = "dirty")))
        assertEquals(PrMergeability(true, false, "checking"), assessMergeability(writer.copy(mergeable = "unknown")))
    }

    @Test
    fun blockedAndBehindBypassForAdmins() {
        assertEquals(PrMergeability(true, false, "blocked"), assessMergeability(writer.copy(mergeStateStatus = "blocked")))
        assertEquals(PrMergeability(true, true, "behind", "bypass"), assessMergeability(writer.copy(mergeStateStatus = "behind", viewer = writer.viewer.copy(canMergeAsAdmin = true))))
    }

    @Test
    fun unstableWarns() = assertEquals(PrMergeability(true, true, warning = "unstable"), assessMergeability(writer.copy(mergeStateStatus = "unstable")))

    @Test
    fun closedIsNotAllowed() = assertEquals(PrMergeability(false, false), assessMergeability(writer.copy(state = "closed")))

    @Test
    fun stateActionsFollowAbilities() {
        assertEquals(listOf("draft", "close"), stateActions(writer).map { it.first })
        assertEquals(listOf("ready", "close"), stateActions(writer.copy(state = "draft")).map { it.first })
        assertEquals(listOf("reopen"), stateActions(writer.copy(state = "closed", viewer = writer.viewer.copy(canReopen = true))).map { it.first })
        assertEquals(emptyList(), stateActions(writer.copy(state = "merged")))
        assertEquals(emptyList(), stateActions(base))
    }

    @Test
    fun revalidatesWhileUnknownOrPending() {
        assertEquals(3_000L, overviewRevalidateDelayMs(writer.copy(mergeable = "unknown")))
        assertEquals(30_000L, overviewRevalidateDelayMs(writer.copy(checksSummary = writer.checksSummary.copy(pending = 2))))
        assertNull(overviewRevalidateDelayMs(writer))
        assertNull(overviewRevalidateDelayMs(writer.copy(state = "merged", mergeable = "unknown")))
    }
}

/** Port of `commit-selection.test.ts`, plus the phone's tap / long-press picking. */
class CommitSelectionTest {
    private fun commit(oid: String) = PrCommit(oid = oid, abbreviatedOid = oid.take(7), headline = oid)
    private val commits = listOf("aaaaaaa1111111111", "bbbbbbb2222222222", "ccccccc3333333333").map(::commit)

    @Test
    fun resolvesBothEndsByPrefix() = assertEquals(0..2, resolveCommitSelection(commits, CommitSelection("aaaaaaa", "ccccccc")))

    @Test
    fun normalizesAReversedRange() = assertEquals(1..2, resolveCommitSelection(commits, CommitSelection("ccccccc", "bbbbbbb")))

    @Test
    fun unknownEndIsNull() = assertNull(resolveCommitSelection(commits, CommitSelection("aaaaaaa", "ddddddd")))

    @Test
    fun shortensToAStablePrefix() = assertEquals(CommitSelection("aaaaaaa11111", "aaaaaaa11111"), selectCommit(commits[0].oid))

    @Test
    fun ordersARangeOldestFirst() {
        assertEquals(CommitSelection("aaaaaaa11111", "ccccccc33333"), selectCommitRange(commits, 2, 0))
        assertEquals(selectCommit(commits[1].oid), selectCommitRange(commits, 1))
    }

    @Test
    fun stepsAroundASingleCommit() {
        assertEquals(selectCommit(commits[0].oid) to selectCommit(commits[2].oid), adjacentCommits(commits, selectCommit(commits[1].oid)))
    }

    @Test
    fun noPreviousAtTheOldestNoNextAtTheNewest() {
        assertNull(adjacentCommits(commits, selectCommit(commits[0].oid)).first)
        assertNull(adjacentCommits(commits, selectCommit(commits[2].oid)).second)
    }

    @Test
    fun stepsOutOfARangeFromItsEnds() {
        assertEquals(selectCommit(commits[0].oid) to null, adjacentCommits(commits, CommitSelection("bbbbbbb", "ccccccc")))
    }

    @Test
    fun unknownSelectionHasNoNeighbours() = assertEquals(null to null, adjacentCommits(commits, CommitSelection("ddddddd", "ddddddd")))

    @Test
    fun tapPicksOneAndTapAgainClears() {
        val one = pickCommit(commits, null, null, 1, extend = false)
        assertEquals(selectCommit(commits[1].oid), one)
        assertNull(pickCommit(commits, one, 1, 1, extend = false))
        assertEquals(selectCommit(commits[2].oid), pickCommit(commits, one, 1, 2, extend = false))
    }

    @Test
    fun extendingSpansFromTheAnchor() {
        val one = selectCommit(commits[2].oid)
        assertEquals(CommitSelection("aaaaaaa11111", "ccccccc33333"), pickCommit(commits, one, 2, 0, extend = true))
        // Without an anchor the current selection's start is the other end.
        assertEquals(CommitSelection("bbbbbbb22222", "ccccccc33333"), pickCommit(commits, CommitSelection("bbbbbbb", "bbbbbbb"), null, 2, extend = true))
    }
}

/** `formatTimeAgo`, `labelStyle` and the `PrHtml` stand-in. */
class OverviewFormatTest {
    private val now = java.time.Instant.parse("2026-10-11T12:00:00Z").toEpochMilli()

    @Test
    fun timeAgoMatchesTheWebUnits() {
        assertEquals("just now", formatTimeAgo(now - 500, now))
        assertEquals("30 s ago", formatTimeAgo(now - 30_000, now))
        assertEquals("5 min ago", formatTimeAgo(now - 5 * 60_000, now))
        assertEquals("2 d ago", prTimeAgo("2026-10-09T12:00:00Z", now))
        assertEquals("in 3 h", formatTimeAgo(now + 3 * 3_600_000, now))
        assertEquals("", prTimeAgo("not a date", now))
    }

    @Test
    fun labelColorsMatchColorJs() {
        // Reference values from @antfu/design `labelStyle` (colorjs.io) for GitHub's `bug` red.
        assertClose(0xFFFF9798, labelColors("d73a4a", dark = true).text)
        assertClose(0xFF3A0007, labelColors("#d73a4a", dark = true).background)
        assertClose(0xFF771B25, labelColors("d73a4a", dark = true).border)
        assertClose(0xFF97001F, labelColors("d73a4a", dark = false).text)
        assertClose(0xFFFFDEDD, labelColors("d73a4a", dark = false).background)
        assertClose(0xFF004C9D, labelColors("0075ca", dark = false).text)
        assertClose(0xFFBEBEBE, labelColors("ededed", dark = true).text)
    }

    private fun assertClose(expected: Long, actual: Long) {
        val channels = listOf(16, 8, 0).map { shift -> (((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)).toInt() }
        assertTrue(channels.all { kotlin.math.abs(it) <= 3 }, "expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF))
    }

    @Test
    fun aFoldedCommentShowsItsFirstLineAsPlainWords() {
        assertEquals("Why this change?", commentPreview("<h2>Why this change?</h2><p>See the cache key.</p>"))
        assertEquals("See the cache key and a note.", commentPreview("<p>See the <a href=\"https://x\"><code>cache key</code></a> and a <strong>note</strong>.</p>"))
        assertEquals("Looks good to me", commentPreview("<p dir=\"auto\">Looks <em>good</em> to me</p><p>second paragraph</p>"))
        assertEquals("first line after the blank ones", commentPreview("<p></p><ul><li>first line after the blank ones</li></ul>"))
        assertEquals("", commentPreview(""))
    }

    @Test
    fun botsAreToolsNotPeople() {
        // GitHub's word decides: the server marks an app's account, and a "[bot]" login is one (N31).
        assertTrue(isBot(PrActor("chatgpt-codex-connector", bot = true)))
        assertTrue(isBot(PrActor("devin-ai-integration", bot = true)))
        assertTrue(isBot(PrActor("github-actions", bot = true)))
        assertTrue(isBot(PrActor("some-app[bot]")))
        assertTrue(isBot(PrActor("Some-App[BOT]")))
        assertFalse(isBot(PrActor("OctavianTocan")))
        assertFalse(isBot(null))
        // No list of names: an account GitHub does not mark is a person, whatever it is called.
        assertFalse(isBot(PrActor("coderabbitai")))
        assertFalse(isBot(PrActor("github-actions")))
    }

    @Test
    fun peopleAndBotsSplitOnTheServersMark() {
        val answer = """{"overview":{"id":"PR_1","number":1,"timeline":[
            {"kind":"comment","id":"a","createdAt":"2026-10-16T06:00:00Z","url":"u","bodyHTML":"<p>hi</p>","actor":{"login":"chatgpt-codex-connector","bot":true}},
            {"kind":"comment","id":"b","createdAt":"2026-10-16T06:01:00Z","url":"u","bodyHTML":"<p>hi</p>","actor":{"login":"OctavianTocan"}},
            {"kind":"review","id":"c","createdAt":"2026-10-16T06:02:00Z","url":"u","state":"commented","bodyHTML":"<p>ok</p>","comments":[],"commentCount":0,"actor":{"login":"devin-ai-integration","bot":true}},
            {"kind":"event","id":"d","createdAt":"2026-10-16T06:03:00Z","event":"labeled","actor":{"login":"github-actions","bot":true}}
        ]}}"""
        val timeline = parsePrOverviewData(Json.parseToJsonElement(answer))!!.overview.timeline
        assertEquals(listOf("b"), timeline.filter { inTalk(it, Talk.People) }.map { it.id })
        assertEquals(listOf("a", "c"), timeline.filter { inTalk(it, Talk.Bots) }.map { it.id })
        assertEquals(4, timeline.count { inTalk(it, Talk.All) })
    }

    @Test
    fun htmlKeepsHeadingsListsAndLinks() {
        val md = htmlToMarkdown(
            """<h2 dir="auto">Why</h2>
<ul dir="auto">
<li>Marking a <strong>large</strong> file as <code>reviewed</code>.</li>
<li>See <a class="user-mention" href="https://github.com/antfu">@antfu</a> &amp; co.</li>
</ul>
<p dir="auto">Line one<br>line two</p>""",
        )
        assertEquals("## Why\n\n- Marking a **large** file as `reviewed`.\n- See **@antfu** \\& co.\n\nLine one\\\nline two", md)
    }

    @Test
    fun htmlKeepsCodeBlocksQuotesAndTasks() {
        val md = htmlToMarkdown(
            """<div class="highlight"><pre><span>val x = 1 &lt; 2</span>
</pre></div><blockquote><p>quoted</p></blockquote><ul class="contains-task-list"><li class="task-list-item"><input type="checkbox" checked disabled> done</li></ul><ol start="3"><li>three</li></ol>""",
        )
        assertEquals("```\nval x = 1 < 2\n```\n\n> quoted\n\n- [x] done\n\n3. three", md)
    }

    @Test
    fun htmlTablesAndDetails() {
        val md = htmlToMarkdown("<table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table><details><summary>More</summary><p>hidden</p></details>")
        assertEquals("| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```$DETAILS_INFO More\nhidden\n```", md)
    }

    @Test
    fun htmlEscapesMarkdownInText() {
        assertEquals("a \\*b\\* \\_c\\_", htmlToMarkdown("<p>a *b* _c_</p>"))
        assertEquals("", htmlToMarkdown(""))
        assertNotNull(htmlToMarkdown("<p>unclosed <em>tag"))
    }

    @Test
    fun parsesTheRealBodyHtml() {
        val data = parsePrOverviewData(Json.parseToJsonElement(javaClass.getResource("/fixtures/pr-overview-106.json")!!.readText()))!!
        val md = htmlToMarkdown(data.overview.bodyHTML)
        assertTrue(md.startsWith("## Why\n\n- Marking a large file"), md)
        assertTrue("## Steps to reproduce" in md)
        val comment = data.overview.timeline.filterIsInstance<PrTimelineItem.Comment>().first()
        assertTrue("**@AlsoKnownAs-Ax**" in htmlToMarkdown(comment.bodyHTML), htmlToMarkdown(comment.bodyHTML))
    }
}

/** Group nav helpers: category maps and per-group progress. */
class GroupNavTest {
    @Test
    fun everyCategoryHasAnIconAndAColor() {
        assertEquals(CATEGORY_ICON.keys, CATEGORY_COLOR.keys)
        assertEquals(16, CATEGORY_ICON.size)
        assertEquals("dots-three-circle-duotone", categoryIcon("unknown"))
    }

    @Test
    fun progressCountsChildrenAndTreatsEmptyAsDone() {
        val files = listOf("a", "b", "c").associateWith { com.octaviantocan.pulls.data.ReviewFile(it, "modified", 1, 2, sha = "sha-$it", patchLines = emptyList()) }
        val group = com.octaviantocan.pulls.data.FileGroup("g", "", listOf("a"), children = listOf(com.octaviantocan.pulls.data.FileGroup("s", "", listOf("b", "c"))))
        assertEquals(1f / 3, groupProgress(group, files, setOf("sha-b")))
        assertEquals(3 to 6, groupStats(group, files))
        assertEquals(1f, groupProgress(com.octaviantocan.pulls.data.FileGroup("e", "", emptyList()), files, emptySet()))
    }

    @Suppress("unused")
    private val unusedJson = JsonObject(emptyMap())
}

/** A description edited since the overview on screen was fetched is put in from `pr-text` (Tavi, 16 Oct 13:52). */
class NewerTextTest {
    private fun answer(updatedAt: String?): JsonObject {
        val at = updatedAt?.let { ",\"updatedAt\":\"$it\"" }.orEmpty()
        return Json.parseToJsonElement("{\"overview\":{\"id\":\"PR_1\",\"number\":1,\"title\":\"Old\",\"body\":\"Old text\",\"bodyHTML\":\"<p>Old text</p>\"$at},\"storedAt\":4}") as JsonObject
    }
    private val text = Json.parseToJsonElement("""{"title":"New","body":"New text","bodyHTML":"<p>New text</p>","updatedAt":"2026-10-16T13:50:00Z"}""") as JsonObject

    @Test
    fun putsInANewerDescription() {
        val patched = parsePrOverviewData(withNewerText(answer("2026-10-16T12:00:00Z"), text))!!.overview
        assertEquals("New", patched.title)
        assertEquals("New text", patched.body)
        assertEquals("<p>New text</p>", patched.bodyHTML)
        assertEquals("PR_1", patched.id)
    }

    @Test
    fun keepsTheRestOfTheAnswer() {
        val patched = withNewerText(answer("2026-10-16T12:00:00Z"), text)!!
        assertEquals("4", patched["storedAt"].toString())
        assertEquals("\"2026-10-16T13:50:00Z\"", (patched["overview"] as JsonObject)["updatedAt"].toString())
    }

    @Test
    fun putsTextIntoAnOverviewWithNoTime() {
        assertEquals("New", parsePrOverviewData(withNewerText(answer(null), text))!!.overview.title)
    }

    @Test
    fun leavesAnOverviewAsNewOrNewer() {
        assertNull(withNewerText(answer("2026-10-16T13:50:00Z"), text))
        assertNull(withNewerText(answer("2026-10-16T14:00:00Z"), text))
    }

    @Test
    fun needsBoth() {
        assertNull(withNewerText(null, text))
        assertNull(withNewerText(answer("2026-10-16T12:00:00Z"), null))
        assertNull(withNewerText(answer("2026-10-16T12:00:00Z"), Json.parseToJsonElement("""{"title":"New"}""") as JsonObject))
    }
}
