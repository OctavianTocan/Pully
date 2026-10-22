package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** One host policy for actual AI readers, mutation controls and their complete paid result chains. */
@Immutable
data class AiCapabilities(
    val canReadResults: Boolean,
    val canAsk: Boolean,
    val canAnalyze: Boolean,
    val canCritique: Boolean,
    val canTour: Boolean,
    val canBrief: Boolean,
) {
    init {
        require(canReadResults || !(canAsk || canAnalyze || canCritique || canTour || canBrief)) {
            "AI actions need their complete result readers."
        }
        require(!canAnalyze || canBrief) { "Standard and Deep analysis need the complete Brief result chain." }
    }

    companion object {
        val None = AiCapabilities(false, false, false, false, false, false)
        val ReadOnly = AiCapabilities(true, false, false, false, false, false)
        val Full = AiCapabilities(true, true, true, true, true, true)
    }
}

/** Uninstalled hosts cannot expose paid or mutation controls. Actual Android/root hosts install one policy. */
val LocalAiCapabilities = staticCompositionLocalOf { AiCapabilities.None }
