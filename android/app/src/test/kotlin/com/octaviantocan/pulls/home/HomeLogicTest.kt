package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.GhLabel
import com.octaviantocan.pulls.ui.home.GhUser
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.LabelStyle
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.PullListQuery
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.RepoPull
import com.octaviantocan.pulls.ui.home.TimeUnit
import com.octaviantocan.pulls.ui.home.asRepoInfo
import com.octaviantocan.pulls.ui.home.asRepoPullsPage
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.home.relativeTime
import com.octaviantocan.pulls.ui.home.inShownRepos
import com.octaviantocan.pulls.ui.home.repoChoices
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.asCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeLinksTest {
    @Test
    fun opensPullsCommitsComparesAndRepos() {
        assertEquals(HomeLink.Target(OpenTarget.Pull("antfu", "pulls.review", 106, "github.com")), HomeLinks.parse("https://github.com/antfu/pulls.review/pull/106/files"))
        assertEquals(HomeLink.Target(OpenTarget.Commit("a", "b", "abc1234")), HomeLinks.parse("github.com/a/b/commit/abc1234/x"))
        assertEquals(HomeLink.Target(OpenTarget.Compare("a", "b", "main", "feat/x")), HomeLinks.parse("https://github.com/a/b/compare/main...feat/x"))
        assertEquals(HomeLink.Repo("a", "b"), HomeLinks.parse("https://github.com/a/b"))
        assertEquals(HomeLink.Repo("a", "b"), HomeLinks.parse("https://github.com/a/b/pulls"))
        assertNull(HomeLinks.parse("https://github.com/a/b/compare/main"))
        assertNull(HomeLinks.parse("hello"))
    }

    @Test
    fun keepsThePhonesShortForms() {
        assertEquals(HomeLink.Target(OpenTarget.Pull("a", "b", 3)), HomeLinks.parse("a/b#3"))
        assertEquals(HomeLink.Target(OpenTarget.Pull("a", "b", 3)), HomeLinks.parse("a/b/3"))
    }

    @Test
    fun theSearchPillOpensALinkOrANumberOnlyOneListedPullRequestHas() {
        val listed = listOf(ListedPull("a", "x", 12), ListedPull("a", "x", 13), ListedPull("b", "y", 13))
        assertEquals(HomeLink.Target(OpenTarget.Pull("a", "x", 12, "github.com")), HomeLinks.fromSearch(" #12 ", listed))
        assertEquals(HomeLink.Target(OpenTarget.Pull("antfu", "pulls.review", 106, "github.com")), HomeLinks.fromSearch("https://github.com/antfu/pulls.review/pull/106", listed))
        assertEquals(HomeLink.Repo("a", "b"), HomeLinks.fromSearch("https://github.com/a/b", listed))
        // Two repositories have 13, and nothing has 99: a number alone then only filters.
        assertNull(HomeLinks.fromSearch("13", listed))
        assertNull(HomeLinks.fromSearch("99", listed))
        assertNull(HomeLinks.fromSearch("fix the cache", listed))
    }

    @Test
    fun splitsRanges() {
        assertEquals("a" to "b", HomeLinks.splitRange("a...b"))
        assertNull(HomeLinks.splitRange("...b"))
        assertNull(HomeLinks.splitRange("a..."))
    }
}

class TimeAgoTest {
    private val now = java.time.Instant.parse("2026-01-10T12:00:00Z").toEpochMilli()

    @Test
    fun picksTheLargestWholeUnit() {
        assertEquals(-3L to TimeUnit.Hour, relativeTime(now - 3 * 60 * 60 * 1000, now))
        assertEquals(-2L to TimeUnit.Day, relativeTime(now - 2 * 24 * 60 * 60 * 1000, now))
        assertEquals(0L to TimeUnit.Second, relativeTime(now - 10_000, now))
        assertEquals(-2L to TimeUnit.Week, relativeTime(now - 14L * 24 * 60 * 60 * 1000, now))
        // `Math.round`: 1.5 hours rounds to 2.
        assertEquals(-1L to TimeUnit.Hour, relativeTime(now - 90 * 60 * 1000 + 1, now))
    }
}

class MyPullsSyncTest {
    private fun pull(n: Int) = PullRow("acme", "app", n, "PR $n", "octo", "2026-10-01T00:00:00Z", false, role = "authored")

    private class Memory(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val items = initial.toMutableMap()
        override fun get(key: String) = items[key]
        override fun set(key: String, value: String) { items[key] = value }
    }

    @Test
    fun showsTheDeviceCopyThenTheServerCacheThenTheFreshList() = runBlocking {
        val seed = MyPullsSync({ null }, { listOf(pull(1)) }, Memory(), now = { 100 })
        val storage = Memory()
        // Seed storage through the same writer the screen uses.
        val writer = MyPullsSync({ PullsSnapshot(listOf(pull(1)), 100) }, { error("unused") }, storage, now = { 100 })
        writer.select("open")
        assertEquals(listOf(1), writer.pulls?.map { it.number })
        assertTrue(seed.pulls == null)

        val cached = CompletableDeferred<PullsSnapshot?>()
        val fresh = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ cached.await() }, { fresh.await() }, storage, now = { 1_000_000 })
        val job = async { sync.select("open") }
        yield()
        assertEquals(listOf(1), sync.pulls?.map { it.number })
        assertTrue(sync.updating)
        cached.complete(PullsSnapshot(listOf(pull(2)), 200, refreshing = true))
        yield()
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        fresh.complete(listOf(pull(3)))
        job.await()
        assertFalse(sync.updating)
        assertEquals(listOf(3), sync.pulls?.map { it.number })
        assertEquals(1_000_000L, sync.fetchedAt)
        assertTrue(storage.items.getValue(MyPullsSync.STORAGE_PREFIX + "open").contains("\"number\":3"))
    }

    @Test
    fun trustsARecentServerCopyUntilAskedToReload() = runBlocking {
        var freshCalls = 0
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull(2)), 990) }, { freshCalls++; listOf(pull(9)) }, now = { 1000 })
        sync.select("open")
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        assertEquals(0, freshCalls)
        sync.reload()
        assertEquals(1, freshCalls)
        assertEquals(listOf(9), sync.pulls?.map { it.number })
    }

    @Test
    fun keepsTheListOnScreenWhenAnUpdateFails() = runBlocking {
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull(2)), 0) }, { error("rate limited") }, now = { 60_000 })
        sync.select("open")
        assertFalse(sync.updating)
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        assertEquals("rate limited", sync.error)
    }

    @Test
    fun dropsAnswersForAStateThatIsNoLongerSelected() = runBlocking {
        val slow = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ null }, { state -> if (state == "open") slow.await() else listOf(pull(5)) })
        val first = async { sync.select("open") }
        yield()
        sync.select("closed")
        assertEquals(listOf(5), sync.pulls?.map { it.number })
        slow.complete(listOf(pull(1)))
        first.await()
        assertEquals(listOf(5), sync.pulls?.map { it.number })
        assertFalse(sync.updating)
    }

    @Test
    fun readsTheServerSnapshot() {
        val snap = Json.parseToJsonElement("""{"pulls":[{"owner":"a","repo":"b","number":1,"title":"t","author":"x","isDraft":false,"updatedAt":"2026-10-11T00:00:00Z","labels":[],"state":"open","role":"owned"}],"fetchedAt":1791743511776,"refreshing":true}""").jsonObject.asSnapshot()!!
        assertEquals(1791743511776, snap.fetchedAt)
        assertTrue(snap.refreshing)
        assertEquals(1, snap.pulls.size)
    }
}

/** Port of `pull-request-list-query.test.ts`. */
class PullListQueryTest {
    private fun item(number: Int, title: String = "PR $number", author: String? = null, labels: List<String> = emptyList(), createdAt: String = "2025-01-01T00:00:00Z", updatedAt: String = "2025-01-01T00:00:00Z", comments: Int = 0) =
        RepoPull(number, title, author = author?.let { GhUser(it) }, labels = labels.map { GhLabel(it, "") }, createdAt = createdAt, updatedAt = updatedAt, comments = comments)

    private val items = listOf(
        item(1, "feat: virtualize the file tree", "antfu", listOf("enhancement"), "2025-01-03T00:00:00Z", "2025-01-05T00:00:00Z", 2),
        item(2, "fix: dark mode flash", "bob", listOf("bug"), "2025-01-01T00:00:00Z", "2025-01-09T00:00:00Z", 7),
        item(30, "docs: typo", "carol", emptyList(), "2025-01-02T00:00:00Z", "2025-01-02T00:00:00Z", 0),
    )

    @Test
    fun keepsEverythingForABlankQuery() {
        assertEquals(items, PullListQuery.filter(items, ""))
        assertEquals(items, PullListQuery.filter(items, "   "))
    }

    @Test
    fun fuzzyMatchesTitles() {
        assertEquals(listOf(2), PullListQuery.filter(items, "drkmode").map { it.number })
        assertEquals(listOf(1), PullListQuery.filter(items, "vft").map { it.number })
    }

    @Test
    fun matchesTheNumberAuthorAndLabels() {
        assertEquals(listOf(30), PullListQuery.filter(items, "#30").map { it.number })
        assertEquals(listOf(30), PullListQuery.filter(items, "carol").map { it.number })
        assertEquals(listOf(1), PullListQuery.filter(items, "enhancement").map { it.number })
    }

    @Test
    fun ordersByTheChosenField() {
        fun numbers(sort: String) = PullListQuery.sort(items, sort).map { it.number }
        assertEquals(listOf(2, 1, 30), numbers("recently-updated"))
        assertEquals(listOf(30, 1, 2), numbers("least-recently-updated"))
        assertEquals(listOf(1, 30, 2), numbers("newest"))
        assertEquals(listOf(2, 30, 1), numbers("oldest"))
        assertEquals(listOf(2, 1, 30), numbers("most-commented"))
        assertEquals(listOf(30, 1, 2), numbers("least-commented"))
        assertEquals(listOf(1, 2, 30), items.map { it.number })
    }

    @Test
    fun readsARepoPullsPage() {
        val page = Json.parseToJsonElement("""{"totalCount":9,"next":"2","items":[{"number":106,"title":"t","url":"u","state":"draft","author":{"login":"x","avatarUrl":"a"},"labels":[{"name":"bug","color":"d73a4a"}],"assignees":[{"login":"y","avatarUrl":""}],"milestone":"v1","createdAt":"c","updatedAt":"u","comments":2,"checks":"failure","reviewDecision":"approved","linkedIssues":1,"additions":46,"deletions":0}]}""").jsonObject.asRepoPullsPage()
        assertEquals(9, page.totalCount)
        assertEquals("2", page.next)
        val pr = page.items.single()
        assertEquals("draft", pr.state)
        assertEquals("x", pr.author?.login)
        assertEquals("d73a4a", pr.labels.single().color)
        assertEquals("v1", pr.milestone)
        assertEquals(46, pr.additions)
        assertEquals("approved", pr.reviewDecision)
        assertNull(Json.parseToJsonElement("""{"totalCount":1,"items":[]}""").jsonObject.asRepoPullsPage().next)
    }
}

class LabelStyleTest {
    private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)

    /** Expected values from `labelStyle` in `@antfu/design` (colorjs.io), run under Node. */
    @Test
    fun matchesTheWebChipColors() {
        val cases = mapOf(
            "d73a4a" to (listOf("#ff9798", "#3a0007", "#771b25") to listOf("#97001f", "#ffdedd", "#f9adac")),
            "a2eeef" to (listOf("#82cdce", "#002020", "#194b4c") to listOf("#00595b", "#d8f1f1", "#a9cccc")),
            "0075ca" to (listOf("#7bc3ff", "#001a3d", "#00447a") to listOf("#004c9d", "#d2efff", "#9ec9f6")),
            "7057ff" to (listOf("#b2b3ff", "#171042", "#3c3482") to listOf("#4632a9", "#e6e8ff", "#bbbdfd")),
            "ededed" to (listOf("#bebebe", "#1b1b1b", "#424242") to listOf("#4d4d4d", "#ebebeb", "#c4c4c4")),
            "e4e669" to (listOf("#c4c545", "#1d1c00", "#474600") to listOf("#525100", "#edefc7", "#c7c991")),
        )
        for ((input, expected) in cases) {
            val dark = LabelStyle.of(input, true)!!
            val light = LabelStyle.of(input, false)!!
            assertClose(expected.first, listOf(dark.text, dark.background, dark.border).map(::hex), input)
            assertClose(expected.second, listOf(light.text, light.background, light.border).map(::hex), input)
        }
        assertNull(LabelStyle.of("nope", true))
    }

    /** Within 2/255 per channel: the gamut search can land a rounding step away. */
    private fun assertClose(expected: List<String>, actual: List<String>, label: String) {
        expected.zip(actual).forEach { (e, a) ->
            val de = (1..5 step 2).map { e.substring(it, it + 2).toInt(16) }
            val da = (1..5 step 2).map { a.substring(it, it + 2).toInt(16) }
            assertTrue(de.zip(da).all { (x, y) -> kotlin.math.abs(x - y) <= 2 }, "$label: expected $expected, got $actual")
        }
    }
}

class RepoInfoTest {
    private val info = Json.parseToJsonElement("""{"currentBranch":"feat/android-app","defaultBranch":{"name":"main","ref":"origin/main"},"branches":["feat/a","main","feat/android-app"],"tags":["v1"],"commits":[{"sha":"3d6c4713a9","subject":"refactor"}]}""").jsonObject.asRepoInfo()

    @Test
    fun readsRepoInfo() {
        assertEquals("feat/android-app", info.currentBranch)
        assertEquals("main" to "origin/main", info.defaultBranch)
        assertEquals(listOf("feat/a", "main", "feat/android-app"), info.branches)
        assertEquals(listOf("3d6c4713a9" to "refactor"), info.commits)
        assertEquals(listOf("v1"), info.tags)
    }
}

class RepoChoicesTest {
    private fun row(owner: String, repo: String, number: Int) = PullRow(
        owner = owner, repo = repo, number = number, title = "t", author = "a", updatedAt = "", isDraft = false,
        role = "authored", checks = "success", updatedAtMs = 0L, additions = 0, deletions = 0,
    )

    private val pulls = listOf(row("human-plus", "ojin", 1), row("human-plus", "ojin", 2), row("OctavianTocan", "pulls.review", 3))

    @Test
    fun listsEveryRepositoryByNameWithItsCount() {
        val choices = repoChoices(pulls, emptySet())
        assertEquals(listOf("human-plus/ojin", "OctavianTocan/pulls.review"), choices.map { it.key })
        assertEquals(listOf(2, 1), choices.map { it.count })
        assertTrue(choices.all { it.shown })
    }

    @Test
    fun aRepositoryThatIsSwitchedOffStaysListedEvenWithNoPullRequests() {
        val choices = repoChoices(pulls, setOf("human-plus/ojin", "luca/labs"))
        assertEquals(listOf("human-plus/ojin", "luca/labs", "OctavianTocan/pulls.review"), choices.map { it.key })
        assertEquals(listOf(false, false, true), choices.map { it.shown })
        assertEquals(0, choices.first { it.key == "luca/labs" }.count)
    }

    @Test
    fun theSearchKeepsRepositoriesWhoseNameContainsIt() {
        assertEquals(listOf("OctavianTocan/pulls.review"), repoChoices(pulls, emptySet(), " PULLS ").map { it.key })
    }

    @Test
    fun theInboxLeavesOutWhatIsSwitchedOff() {
        assertEquals(listOf(3), pulls.inShownRepos(setOf("human-plus/ojin")).map { it.number })
        assertEquals(3, pulls.inShownRepos(emptySet()).size)
    }
}

class AiSettingsLogicTest {
    private val catalog = Json.parseToJsonElement("""{"engine":"codex","version":"0.159.3","account":"Pro","models":[{"id":"gpt-6.1-sol","name":"GPT-6.1-Sol","description":"d","efforts":["low","medium","ultra"],"defaultEffort":"low","isDefault":true},{"id":"mini","name":"Mini","efforts":[]}]}""").jsonObject.asCatalog()

    @Test
    fun readsTheCatalogAndItsLine() {
        assertEquals(2, catalog.models.size)
        assertEquals("Pro · 0.159.3", catalog.line)
        assertTrue(catalog.models[0].isDefault)
        assertEquals("low", catalog.models[0].defaultEffort)
    }

    @Test
    fun keepsAnEffortOnlyWhenTheNewModelListsIt() {
        assertEquals("medium", AiSettingsLogic.keptEffort(catalog, "gpt-6.1-sol", "medium"))
        assertEquals("", AiSettingsLogic.keptEffort(catalog, "mini", "medium"))
        assertEquals("", AiSettingsLogic.keptEffort(catalog, "gpt-6.1-sol", ""))
    }

    @Test
    fun labelsEfforts() {
        assertEquals("ai.effort.xhigh", AiSettingsLogic.effortLabel("xhigh") { it })
        assertEquals("Turbo", AiSettingsLogic.effortLabel("turbo") { it })
    }

    @Test
    fun aBlankModelIsTheDefault() {
        assertEquals("gpt-6.1-sol", AiSettingsLogic.current(catalog, "")?.id)
        assertEquals("mini", AiSettingsLogic.current(catalog, "mini")?.id)
        assertNull(AiSettingsLogic.current(catalog, "custom"))
    }
}
