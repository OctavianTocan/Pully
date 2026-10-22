package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AccountSettingsSync
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsSyncStore
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.ui.design.ColorThemes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsThemePickerTest {
    @Test
    fun aRetainedNestedSettingsChoiceCannotWriteReplacementPreferencesOrSettingsSync() = runTest {
        val h = Harness(this)
        val drawn = h.services.colorThemes
        h.replace(closeOriginal = true)
        drawn.choose(backgroundScope, h.target, dark = false, motion = false)
        runCurrent(); advanceTimeBy(1_501); runCurrent()
        h.assertUnchanged()
        h.services.close()
    }

    @Test
    fun aQueuedNestedChoiceRechecksTheSourceAndTheCurrentPickerStillPersistsAndSyncs() = runTest {
        val h = Harness(this)
        val drawn = h.services.colorThemes
        drawn.choose(backgroundScope, h.target, dark = false, motion = false)
        h.replace(closeOriginal = false) // The old composition/services have not been disposed yet.
        runCurrent(); advanceTimeBy(1_501); runCurrent()
        h.assertUnchanged()

        h.services.colorThemes.choose(backgroundScope, h.target, dark = false, motion = false)
        runCurrent()
        assertEquals(h.target.id, h.preferences.getString("colorTheme", null))
        assertEquals(listOf("colorTheme"), h.changes)
        assertEquals(listOf("colorTheme"), h.touches)
        advanceTimeBy(1_501); runCurrent()
        assertEquals(1, h.synced.size)
        assertTrue(h.synced.single().first.startsWith("https://second.example/"))
        assertTrue(h.synced.single().second.contains(h.target.id))
        h.services.close()
    }

    private class Harness(private val scope: TestScope) {
        val preferences = MemorySettingsPreferences()
        val changes = mutableListOf<String>()
        val touches = mutableListOf<String>()
        val synced = mutableListOf<Pair<String, String>>()
        private var account = "first"
        private var generation = 0L
        private lateinit var sync: AccountSettingsSync
        private var store = SyncStore()
        val settings = AppSettings(preferences, object : SettingsEffects {
            override fun touched(name: String) { touches += name; sync.touched(name) }
            override fun settingsChanged(key: String, value: String) { changes += key }
            override fun usageStatsEnabled(enabled: Boolean) {}
        }) { "en" }
        private val initial = settings.colorTheme
        val target = ColorThemes.all.first { it.id != initial }
        private val repo = Repo({ RepoConfiguration("https://$account.example", generation = generation) }, RpcTransport {
            synced += it.url to it.body
            RpcResponse(200, """{"result":{}}""")
        })
        var services = createServices()
            private set

        init { installSync() }

        fun replace(closeOriginal: Boolean) {
            if (closeOriginal) services.close()
            account = "second"
            generation++
            store = SyncStore()
            installSync()
            services = createServices()
        }

        fun assertUnchanged() {
            assertEquals(initial, settings.colorTheme)
            assertNull(preferences.getString("colorTheme", null))
            assertTrue(changes.isEmpty())
            assertTrue(touches.isEmpty())
            assertTrue(store.written.isEmpty())
            assertTrue(synced.isEmpty())
        }

        private fun installSync() {
            sync = AccountSettingsSync(settings, repo, store, { account }, scope.backgroundScope,
                { scope.testScheduler.currentTime + 10_000 })
        }

        private fun createServices(): SettingsServices {
            val opening = account
            val current = { account == opening }
            val models = SourceModelSettingsServices(repo, preferences, { ModelConfiguration("codex", "fixture") }, {}, current)
            val accountPages = SourceAccountPagesServices(repo, { "$opening@example.test" }, current, {}, {})
            val stats = SourceStatsServices(repo, preferences, { true }, current)
            val feedback = SourceFeedbackServices(repo, settings, FeedbackMetadata("test", 1, "test", "test", "test"), { "settings" }, { null }, current)
            return SettingsServices(settings, models, accountPages, stats, feedback, { error("No server page") },
                object : SettingsFeedback {
                    override fun vibrationOn() = Unit
                    override fun soundsChanged(enabled: Boolean) = Unit
                    override fun soundSelected(direction: Direction?) = Unit
                }, SettingsPlatformContent(emptySet(), {}, {}, {}, {}, { _, _ -> error("No platform page") }), current, {})
        }
    }

    private class SyncStore : SettingsSyncStore {
        override var seeded = false
        override var owner: String? = null
        val written = mutableMapOf<String, Long>()
        override fun times(names: List<String>) = written.filterKeys { it in names }
        override fun write(times: Map<String, Long>, seeded: Boolean?, owner: String?) {
            written.putAll(times)
            if (seeded != null) this.seeded = seeded
            if (owner != null) this.owner = owner
        }
    }
}
