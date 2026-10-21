package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.DETAILS_INFO

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `pr-overview` parsing against a real server answer (antfu/pulls.review#106). */
class OverviewParsingTest {
    private val fixture = OverviewFixture.json
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

    private fun mergeAnswer(mergeable: String? = "mergeable", status: String? = "clean", fresh: Boolean = true, expectedHead: String? = null): PrOverviewData {
        val fields = listOfNotNull(mergeable?.let { "\"mergeable\":\"$it\"" }, status?.let { "\"mergeStateStatus\":\"$it\"" }).joinToString(",")
        return parsePrOverviewData(Json.parseToJsonElement("""{"overview":{"id":"x","state":"open","head":{"oid":"head-1"},"viewer":{"canPush":true},"merge":{"methods":["merge"]}${if (fields.isNotEmpty()) ",$fields" else ""}},"mergeability":{"allowed":true,"ready":true}}"""), fresh = fresh, expectedHead = expectedHead)!!
    }

    @Test
    fun rawConflictOrUnknownOverridesContradictoryReadyEnvelope() {
        assertEquals("conflicts", mergeAnswer(mergeable = "conflicting").mergeability.blocker)
        assertFalse(mergeAnswer(status = "dirty").mergeability.ready)
        for (unknown in listOf(null, "", "unknown", "new-state")) {
            val parsed = mergeAnswer(mergeable = unknown)
            assertEquals("unknown", parsed.overview.mergeable)
            assertEquals(PrMergeability(true, false, "checking"), parsed.mergeability)
            assertEquals("unknown", mergeAnswer(status = unknown).overview.mergeStateStatus)
            assertFalse(mergeAnswer(status = unknown).mergeability.ready)
        }
        assertEquals("conflicts", mergeAnswer(mergeable = "CONFLICTING", status = "DIRTY").mergeability.blocker)
    }

    @Test
    fun cachedReadinessWaitsForFreshOverviewWhileConflictsRemainVisible() {
        assertEquals(PrMergeability(true, false, "checking"), mergeAnswer(fresh = false).mergeability)
        assertEquals(PrMergeability(true, false, "conflicts"), mergeAnswer(mergeable = "conflicting", fresh = false).mergeability)
        assertEquals(PrMergeability(true, true), mergeAnswer(fresh = true).mergeability)
    }

    @Test
    fun observedNewerHeadInvalidatesOldReadinessUntilMatchingOverviewArrives() {
        assertEquals(PrMergeability(true, false, "checking"), mergeAnswer(expectedHead = "head-2").mergeability)
        assertEquals(PrMergeability(true, true), mergeAnswer(expectedHead = "head-1").mergeability)
        assertEquals("conflicts", mergeAnswer(mergeable = "conflicting", expectedHead = "head-2").mergeability.blocker)
    }

    @Test
    fun cleanCachedOrOldHeadOverviewRetriesUntilFreshMergeabilityArrives() {
        assertEquals(3_000L, overviewRevalidateDelayMs(mergeAnswer(fresh = false)))
        assertEquals(3_000L, overviewRevalidateDelayMs(mergeAnswer(expectedHead = "head-2")))
        assertNull(overviewRevalidateDelayMs(mergeAnswer(expectedHead = "head-1")))
        val closed = mergeAnswer(fresh = false).let { it.copy(overview = it.overview.copy(state = "closed")) }
        assertNull(overviewRevalidateDelayMs(closed))
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

/** Lifecycle truth must survive cached headers, stale verdicts and independently loaded checks. */
class PrLifecycleTest {
    private fun answer(state: String?, mergedAt: String? = null, closedAt: String? = null, fresh: Boolean = true, observed: String? = null): PrOverviewData = parsePrOverviewData(
        buildJsonObject {
            put("overview", buildJsonObject {
                put("id", "PR_1")
                state?.let { put("state", it) }
                mergedAt?.let { put("mergedAt", it) }
                closedAt?.let { put("closedAt", it) }
                put("mergeable", "mergeable")
                put("mergeStateStatus", "clean")
                put("viewer", buildJsonObject { put("canPush", true) })
                put("merge", Json.parseToJsonElement("""{"methods":["merge"]}"""))
                put("checksSummary", Json.parseToJsonElement("""{"total":1,"passing":1,"state":"success"}"""))
            })
            put("mergeability", buildJsonObject { put("allowed", true); put("ready", true) })
        }, fresh = fresh, observedState = observed,
    )!!

    @Test
    fun mergedStateAndTimestampTakePrecedenceOverClosedOrDraft() {
        for (state in listOf("merged", "MERGED")) assertEquals("merged", answer(state).overview.state)
        for (state in listOf("closed", "draft", "open")) {
            val merged = answer(state, mergedAt = "2026-10-20T12:00:00Z", closedAt = "2026-10-20T12:00:00Z")
            assertEquals("merged", merged.overview.state)
            assertEquals("success", merged.overview.checksSummary.state)
            assertEquals(PrMergeability(false, false), merged.mergeability)
            assertEquals(emptyList(), stateActions(merged.overview))
            assertNull(overviewMergeBlocker(merged.overview))
        }
        assertEquals("closed", answer("draft", closedAt = "2026-10-20T12:00:00Z").overview.state)
        assertEquals("closed", answer("CLOSED").overview.state)
    }

    @Test
    fun freshMergedObservationCannotRegressBehindAnOpenOverview() {
        for (fresh in listOf(false, true)) {
            val merged = answer("open", fresh = fresh, observed = "merged")
            assertEquals("merged", merged.overview.state)
            assertFalse(merged.mergeability.allowed)
            assertFalse(merged.mergeability.ready)
        }
        assertEquals("merged", answer("merged", fresh = false, observed = "open").overview.state)
        assertEquals("closed", answer("open", fresh = false, observed = "closed").overview.state)
    }

    @Test
    fun freshOverviewCanReopenAClosedCopyWithoutInheritingUnknownAsOpen() {
        assertEquals("open", answer("open", observed = "closed").overview.state)
        for (state in listOf(null, "", "unknown", "NEW_STATE")) {
            val unknown = answer(state, observed = "open")
            assertEquals("unknown", unknown.overview.state)
            assertFalse(unknown.overview.isOpen)
            assertFalse(unknown.mergeability.ready)
            assertFalse(unknown.mergeability.allowed)
        }
    }

    @Test
    fun headerActionsRequireKnownOpenStateAndPreviewDoesNotInventOpen() {
        fun head(state: String) = PrHead("Title", "#1", "", state, "main", "feature", null, null, "owner", "repo")
        for (state in listOf("merged", "closed", "unknown", "")) assertFalse(head(state).isOpen)
        for (state in listOf("open", "draft")) assertTrue(head(state).isOpen)
        assertEquals("unknown", PullPreview("Title").state)
        assertEquals("draft", PullPreview("Title", isDraft = true).state)
    }
}

/** `formatTimeAgo`, `labelStyle` and the `PrHtml` stand-in. */
class OverviewFormatTest {
    private val now = checkNotNull(prIsoMillis("2026-10-11T12:00:00Z"))

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
        assertTrue(channels.all { kotlin.math.abs(it) <= 3 }, "expected #${(expected and 0xFFFFFF).toString(16)}, got #${(actual and 0xFFFFFF).toString(16)}")
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
        val data = parsePrOverviewData(Json.parseToJsonElement(OverviewFixture.json))!!
        val md = htmlToMarkdown(data.overview.bodyHTML)
        assertTrue(md.startsWith("## Why\n\n- Marking a large file"), md)
        assertTrue("## Steps to reproduce" in md)
        val comment = data.overview.timeline.filterIsInstance<PrTimelineItem.Comment>().first()
        assertTrue("**@AlsoKnownAs-Ax**" in htmlToMarkdown(comment.bodyHTML), htmlToMarkdown(comment.bodyHTML))
    }
}

/** A description edited since the overview on screen was fetched is put in from `pr-text` (Tavi, 16 Oct 13:52). */
class NewerTextTest {
    private fun answer(updatedAt: String?): JsonObject {
        val at = updatedAt?.let { ",\"updatedAt\":\"$it\"" }.orEmpty()
        return Json.parseToJsonElement(
            "{\"overview\":{\"id\":\"PR_1\",\"number\":7,\"title\":\"Old\",\"body\":\"Old text\",\"bodyHTML\":\"<p>Old text</p>\"$at}," +
                "\"defaultCommits\":{\"squash\":{\"title\":\"Old (#7)\",\"body\":\"Old text\"},\"merge\":{\"title\":\"Merge pull request #7 from a/b\",\"body\":\"Old\"}," +
                "\"rebase\":{\"title\":\"\",\"body\":\"\"}},\"storedAt\":4}",
        ) as JsonObject
    }
    private val text = Json.parseToJsonElement("""{"title":"New","body":"New text","bodyHTML":"<p>New text</p>","updatedAt":"2026-10-16T13:50:00Z"}""") as JsonObject

    @Test
    fun putsInTheDescription() {
        val patched = parsePrOverviewData(withText(answer("2026-10-16T12:00:00Z"), text))!!.overview
        assertEquals("New", patched.title)
        assertEquals("New text", patched.body)
        assertEquals("<p>New text</p>", patched.bodyHTML)
        assertEquals("PR_1", patched.id)
    }

    @Test
    fun putsItInWithinTheSameSecond() {
        // GitHub's `updatedAt` counts whole seconds: an edit can carry the time of the copy it replaced.
        assertEquals("New text", parsePrOverviewData(withText(answer("2026-10-16T13:50:00Z"), text))!!.overview.body)
        assertEquals("New", parsePrOverviewData(withText(answer(null), text))!!.overview.title)
    }

    @Test
    fun keepsTheRestOfTheAnswer() {
        val patched = withText(answer("2026-10-16T12:00:00Z"), text)!!
        assertEquals("4", patched["storedAt"].toString())
        assertEquals("\"2026-10-16T13:50:00Z\"", (patched["overview"] as JsonObject)["updatedAt"].toString())
    }

    @Test
    fun rewritesTheMergeCommitTextMadeFromTheOldOne() {
        val defaults = parsePrOverviewData(withText(answer("2026-10-16T12:00:00Z"), text))!!.defaultCommits
        assertEquals("New (#7)" to "New text", defaults["squash"])
        assertEquals("Merge pull request #7 from a/b" to "New", defaults["merge"])
        assertEquals("" to "", defaults["rebase"])
    }

    @Test
    fun needsBoth() {
        assertNull(withText(null, text))
        assertNull(withText(answer("2026-10-16T12:00:00Z"), null))
        assertNull(withText(answer("2026-10-16T12:00:00Z"), Json.parseToJsonElement("""{"updatedAt":"x"}""") as JsonObject))
    }
}
