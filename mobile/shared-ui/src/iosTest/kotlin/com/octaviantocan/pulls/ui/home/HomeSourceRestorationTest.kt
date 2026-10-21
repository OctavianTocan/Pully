package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs against the actual Compose Native runtime; JVM Android stubs cannot create a composition. */
class HomeSourceRestorationTest {
    @Test
    fun savedRouteInputRestoresForItsOwnerButNotAfterAnOffscreenSourceChange() {
        val first = homeScreenDataFixture()
        val original = SavedRouteInput(first)
        original.input.value = "private repository search"
        val kept = original.registry.performSave()
        original.dispose()
        val sameOwner = SavedRouteInput(first, kept)
        assertEquals("private repository search", sameOwner.input.value)
        sameOwner.dispose()
        val nextOwner = SavedRouteInput(homeScreenDataFixture(generation = 1), kept)
        assertEquals("", nextOwner.input.value)
        nextOwner.dispose()
    }
}

private class SavedRouteInput(owner: HomeScreenData, restored: Map<String, List<Any?>>? = null) {
    val registry = SaveableStateRegistry(restored) { true }
    lateinit var input: MutableState<String>
    private val recomposer = Recomposer(EmptyCoroutineContext)
    private val composition = Composition(RouteInputApplier(), recomposer)
    init {
        composition.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                SourceOwnedScreen(owner) {
                    val text = rememberSaveable { mutableStateOf("") }
                    SideEffect { input = text }
                }
            }
        }
    }
    fun dispose() { composition.dispose(); recomposer.cancel() }
}

private class RouteInputApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
