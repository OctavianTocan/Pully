package com.octaviantocan.pulls.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.RepoIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
private fun rememberAndroidCommandPaletteOwner(): CommandPaletteOwner {
    fun create(): Pair<Long, CommandPaletteOwner> {
        val epoch = RepoIdentity.generation
        return epoch to CommandPaletteOwner { epoch == RepoIdentity.generation }
    }
    var binding by remember { mutableStateOf(create()) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        fun changed() {
            val next = RepoIdentity.generation
            if (next == binding.first) return
            binding.second.close()
            binding = create()
        }
        val stop = RepoIdentity.observeInvalidation { scope.launch(Dispatchers.Main.immediate) { changed() } }
        // Compare with the owner's captured epoch, including changes before observer registration.
        changed()
        onDispose { stop(); binding.second.close() }
    }
    return binding.second
}

@Composable
fun CommandPalette() = CommandPalette(rememberAndroidCommandPaletteOwner())

@Composable
internal fun ColumnScope.CommandsPage(scope: PaletteScope?, onClearScope: () -> Unit, onRun: (Command) -> Unit) =
    CommandsPage(rememberAndroidCommandPaletteOwner(), scope, onClearScope, onRun)
