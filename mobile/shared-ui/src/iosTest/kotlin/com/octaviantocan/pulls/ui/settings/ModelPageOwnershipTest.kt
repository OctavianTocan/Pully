package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.aikit.CliCatalog
import com.octaviantocan.aikit.CliModel
import com.octaviantocan.pulls.ui.review.ModelPageOwner
import com.octaviantocan.pulls.ui.review.ModelPageState
import com.octaviantocan.pulls.ui.review.rememberModelPageState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelPageOwnershipTest {
    @Test
    fun sameEngineSourceReplacementDiscardsActualPickerInputAndOldRowsCannotWrite() = runTest {
        val first = Services("first")
        val replacement = Services("replacement") // Same engine, different account/catalog/configuration.
        val owner = mutableStateOf<ModelSettingsServices>(first)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var mounted: ModelPageState
        try {
            composition.setContent {
                ModelPageOwner(owner.value) {
                    mounted = rememberModelPageState(owner.value)
                    mounted.query // Observe the real input used by ModelPage's SheetSearch.
                    mounted.loaded
                }
            }
            runCurrent()
            val previous = mounted
            assertEquals("first", previous.loaded?.getOrThrow()?.models?.single()?.id)
            previous.query = "private account search"
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(1L); runCurrent()
            assertSame(previous, mounted)
            assertEquals("private account search", mounted.query)
            first.active = false
            owner.value = replacement
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(2L); runCurrent()
            assertNotSame(previous, mounted)
            assertEquals("", mounted.query)
            assertEquals("replacement", mounted.config.model)
            assertEquals("replacement", mounted.loaded?.getOrThrow()?.models?.single()?.id)
            assertFalse(previous.save(ModelConfiguration("codex", "first")))
            assertEquals(emptyList(), replacement.writes)
            mounted.save(ModelConfiguration("codex", "chosen"))
            assertEquals(listOf(ModelConfiguration("codex", "chosen")), replacement.writes)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
        }
    }

    private class Services(private val model: String) : ModelSettingsServices {
        var active = true
        val writes = mutableListOf<ModelConfiguration>()
        private val catalog = CliCatalog(listOf(CliModel(model, model, null, emptyList(), null, true)), null, null)
        override fun current() = active
        override fun configuration() = ModelConfiguration("codex", model)
        override fun save(configuration: ModelConfiguration) { writes += configuration }
        override fun remembered(engine: String): String? = null
        override fun remember(engine: String, model: String) = Unit
        override fun cached(engine: String): CliCatalog? = if (engine == "codex") catalog else null
        override suspend fun fetch(engine: String, refresh: Boolean) = Result.success(catalog)
    }
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
