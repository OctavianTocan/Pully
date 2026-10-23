package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.JEV_FILE_WORDS
import com.octaviantocan.pulls.data.JevFiles
import com.octaviantocan.pulls.data.PullJev
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.asJevFiles
import com.octaviantocan.pulls.data.asPull
import com.octaviantocan.pulls.data.asPulls
import com.octaviantocan.pulls.data.kindWords
import com.octaviantocan.pulls.data.riskWords
import com.octaviantocan.pulls.ui.home.InboxGrouping
import com.octaviantocan.pulls.ui.home.InboxLayout
import com.octaviantocan.pulls.ui.home.InboxSort
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.UNSORTED_LABEL
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.home.inboxSections
import com.octaviantocan.pulls.ui.home.repoLabels
import com.octaviantocan.pulls.ui.home.toJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class JevTestStore : KeyValueStore {
    val data = HashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}

/** Jev on Home: the rows' kind and risk, grouping by kind, Riskiest first, what happens while Jev is off, and the file marks. */
class JevInboxTest {
    private val server = """
        [{"owner":"acme","repo":"app","number":12,"title":"Fix the crash","author":"octo","isDraft":false,"updatedAt":"2026-10-11T00:00:00Z",
          "labels":[],"state":"open","role":"authored","jev":{"kind":"fix","kindConfidence":0.82,"risk":1.4}},
         {"owner":"acme","repo":"app","number":13,"title":"Not yet","author":"octo","isDraft":false,"updatedAt":"2026-10-10T00:00:00Z",
          "labels":[],"state":"open","role":"authored"},
         {"owner":"acme","repo":"app","number":14,"title":"Broken","author":"octo","isDraft":false,"updatedAt":"2026-10-10T00:00:00Z",
          "labels":[],"state":"open","role":"authored","jev":{"kindConfidence":0.5}}]
    """.trimIndent()

    private fun pull(number: Int, repo: String = "app", owner: String = "acme", kind: String? = null, risk: Double = 0.0, updatedAtMs: Long = number.toLong()) =
        PullRow(owner = owner, repo = repo, number = number, title = "Change $number", author = "devin", updatedAt = "", isDraft = false, updatedAtMs = updatedAtMs, jev = kind?.let { PullJev(it, 0.9, risk) })

    @Test fun readsJevOnARowAndIgnoresOneWithoutAKind() {
        val (fix, unsorted, broken) = Json.parseToJsonElement(server).asPulls()
        assertEquals(PullJev("fix", 0.82, 1.4), fix.jev)
        assertNull(unsorted.jev)
        assertNull(broken.jev)
    }

    @Test fun theDeviceCopyKeepsJev() {
        val rows = Json.parseToJsonElement(server).asPulls()
        assertEquals(rows, JsonArray(rows.map { it.toJson() }).asPulls())
        assertEquals(rows, rows.map { Json.parseToJsonElement(it.toJson().toString()).jsonObject.asPull() })
    }

    @Test fun theSnapshotSaysWhetherJevIsOn() {
        fun snapshot(extra: String) = Json.parseToJsonElement("""{"pulls":[],"fetchedAt":5,"refreshing":false$extra}""").jsonObject.asSnapshot()!!
        assertTrue(snapshot(""","classifier":"jev"""").jev)
        assertFalse(snapshot("").jev)
        assertFalse(snapshot(""","classifier":"other"""").jev)
    }

    @Test fun jevFollowsTheServerSnapshotAndSurvivesARestart() = runTest {
        val store = JevTestStore()
        var on = true
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull(1)), 0, jev = on) }, { listOf(pull(2)) }, store, now = { 60_000 })
        sync.select("open")
        // The fresh list is a plain array that says nothing either way, so Jev stays on.
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        assertTrue(sync.jev)

        val restarted = MyPullsSync({ null }, { error("offline") }, store)
        restarted.select("open")
        assertTrue(restarted.jev, "the device copy remembers it, so a saved Kind grouping does not jump on launch")

        on = false
        sync.reload()
        assertFalse(sync.jev, "a snapshot without the classifier turns it off")
    }

    @Test fun kindsComeInWhileTheInboxIsOpen() = runTest {
        var reads = 0
        val asked = mutableListOf<Boolean>()
        val sync = MyPullsSync(
            cached = { PullsSnapshot(listOf(pull(1), pull(2)), 0, jev = true) },
            fresh = { error("the server's copy is recent") },
            now = { 1_000 },
            peek = { _, classify -> reads++; asked += classify; PullsSnapshot(listOf(pull(1, kind = "fix"), pull(2, kind = "docs".takeIf { reads >= 2 })), 0, jev = true) },
        )
        sync.select("open")
        sync.wantsJev = true
        assertTrue(sync.sorting)
        sync.followJev(everyMs = 1)
        assertEquals(2, reads, "it stops once every row has its kind")
        assertEquals(listOf(true, true), asked, "each read asks Jev to sort what it has not")
        assertEquals(listOf("fix", "docs"), sync.pulls?.map { it.jev?.kind })
        assertFalse(sync.sorting)
    }

    @Test fun asksJevNothingUnlessTheInboxIsSortedByRiskOrGroupedByKind() = runTest {
        var reads = 0
        val sync = MyPullsSync(
            cached = { PullsSnapshot(listOf(pull(1), pull(2)), 0, jev = true) },
            fresh = { error("the server's copy is recent") },
            now = { 1_000 },
            peek = { _, _ -> reads++; null },
        )
        sync.select("open")
        assertFalse(sync.sorting, "rows without a kind are normal: Jev reads a pull request when it is reviewed")
        sync.followJev(everyMs = 1)
        assertEquals(0, reads)
    }

    @Test fun stopsWaitingForKindsAfterAWhileAndTakesNoneForAnotherHead() = runTest {
        var clock = 0L
        var reads = 0
        val sync = MyPullsSync(
            cached = { PullsSnapshot(listOf(pull(1).copy(headSha = "new")), 0, jev = true) },
            fresh = { error("the server's copy is recent") },
            now = { clock },
            peek = { _, _ -> reads++; clock += 1_000; PullsSnapshot(listOf(pull(1, kind = "fix").copy(headSha = "old")), 0, jev = true) },
        )
        sync.select("open")
        sync.wantsJev = true
        sync.followJev(everyMs = 1, forMs = 2_500)
        assertEquals(3, reads)
        assertNull(sync.pulls?.single()?.jev)
    }

    @Test fun groupsByKindInAFixedOrderWithTheUnsortedLast() {
        val sorted = InboxSort.Number.apply(
            listOf(
                pull(1, kind = "docs"), pull(2, kind = "fix"), pull(3), pull(4, kind = "feature"),
                pull(5, kind = "fix", repo = "web"), pull(6, kind = "made-up"), pull(7, kind = "infra"),
            ),
        )
        val sections = inboxSections(sorted, InboxGrouping.Kind)
        assertEquals(listOf("Fixes", "Features", "Docs", "Infra", UNSORTED_LABEL), sections.map { it.label })
        assertEquals(listOf("kind:fix", "kind:feature", "kind:docs", "kind:infra", "kind:none"), sections.map { it.key })
        // Each heading keeps the sort: PR number, highest first.
        assertEquals(listOf(5, 2), sections[0].pulls.map { it.number })
        assertEquals(listOf(6, 3), sections.last().pulls.map { it.number })
        assertTrue(sections.all { it.repo == null }, "a kind's name opens nothing")
    }

    @Test fun groupsByRepositoryAsBefore() {
        val sections = inboxSections(listOf(pull(1, repo = "web"), pull(2), pull(3, repo = "web")), InboxGrouping.Repo)
        assertEquals(listOf("acme/web", "acme/app"), sections.map { it.key })
        assertEquals(listOf("web", "app"), sections.map { it.label })
        assertEquals("acme/web", sections.first().repo?.name)
    }

    @Test fun rowsUnderAKindNameTheirRepositoryAsItsHeadingWould() {
        val labels = repoLabels(listOf(pull(1), pull(2, repo = "web"), pull(3, owner = "other", repo = "web")))
        assertEquals(mapOf("acme/app" to "app", "acme/web" to "acme/web", "other/web" to "other/web"), labels)
    }

    @Test fun riskiestFirstPutsTheUnsortedLast() {
        val pulls = listOf(pull(1, kind = "fix", risk = 0.4), pull(2), pull(3, kind = "infra", risk = 2.6), pull(4, kind = "docs", risk = 0.4), pull(5))
        assertEquals(listOf(3, 1, 4, 2, 5), InboxSort.Risk.apply(pulls).map { it.number })
    }

    @Test fun riskAndKindAreOfferedOnlyWhileJevIsOn() {
        assertFalse(InboxSort.Risk in InboxSort.offered(jev = false))
        assertTrue(InboxSort.Risk in InboxSort.offered(jev = true))
        assertEquals(listOf(InboxGrouping.Repo), InboxGrouping.offered(jev = false))
        assertEquals(listOf(InboxGrouping.Repo, InboxGrouping.Kind), InboxGrouping.offered(jev = true))
    }

    @Test fun aSavedRiskSortOrKindGroupingFallsBackWhileJevIsOffAndReturnsWithIt() {
        val store = JevTestStore()
        val layout = InboxLayout(store)
        assertEquals(InboxGrouping.Repo, layout.groupBy)
        layout.updateSort(InboxSort.Risk)
        layout.updateGroupBy(InboxGrouping.Kind)
        layout.toggle("kind:fix")

        val again = InboxLayout(store)
        assertEquals(InboxSort.Updated, again.sortWhen(jev = false))
        assertEquals(InboxGrouping.Repo, again.groupByWhen(jev = false))
        assertEquals(InboxSort.Risk, again.sortWhen(jev = true))
        assertEquals(InboxGrouping.Kind, again.groupByWhen(jev = true))
        assertTrue(again.isFolded("kind:fix"), "kind folds share the repositories' set")
        assertEquals(InboxGrouping.Repo, InboxGrouping.of("nonsense"))
    }

    @Test fun readsTheFileMarks() {
        val answer = Json.parseToJsonElement(
            """{"enabled":true,"files":{"src/auth.ts":{"priority":"p0","confidence":0.31},"pnpm-lock.yaml":{"priority":"p2","confidence":0.95},"odd":{}},"pending":3}""",
        ).jsonObject.asJevFiles()
        assertEquals(JevFiles(true, mapOf("src/auth.ts" to "p0", "pnpm-lock.yaml" to "p2"), 3), answer)
        assertEquals(JevFiles(false, emptyMap(), 0), Json.parseToJsonElement("""{"enabled":false,"files":{},"pending":0}""").jsonObject.asJevFiles())
    }

    @Test fun readsWhatJevMadeOfThePullRequest() {
        val answer = Json.parseToJsonElement(
            """{"enabled":true,"files":{},"pending":0,"pull":{"kind":"fix","kindConfidence":0.6,"risk":2.7}}""",
        ).jsonObject.asJevFiles()
        assertEquals(PullJev("fix", 0.6, 2.7), answer.pull)
    }

    @Test fun saysTheKindWithHowSureAndTheRiskInWords() {
        assertEquals("Fix", PullJev("fix", 0.9, 0.0).kindWords)
        assertEquals("Likely a feature", PullJev("feature", 0.6, 0.0).kindWords)
        assertEquals("Maybe docs", PullJev("docs", 0.3, 0.0).kindWords)
        assertNull(PullJev("made-up", 0.9, 0.0).kindWords)
        assertEquals(listOf("Trivial", "Low risk", "Medium risk", "High risk", "High risk"), listOf(0.2, 1.4, 1.6, 2.5, 3.4).map { PullJev("fix", 1.0, it).riskWords })
        assertEquals(mapOf("p0" to "Needs a look", "p1" to "Worth a look", "p2" to "Skim"), JEV_FILE_WORDS)
    }
}
