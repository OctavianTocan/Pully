package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * What pressing looks like everywhere: the pressed thing takes a flat tint (`active`) for as long as it is held,
 * and nothing else changes. No ripple spreads from the finger and nothing scales, shifts or grows (rule 82).
 * Provided as `LocalIndication` by `PullsTheme`, so every `clickable` in the app gets it.
 */
object PullsIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressTintNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === PullsIndication

    override fun hashCode(): Int = 0x50554c4c
}

private class PressTintNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val tint = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> launch { tint.animateTo(1f, tween(PullsMotion.MICRO)) }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch { tint.animateTo(0f, tween(PullsMotion.QUICK)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val strength = tint.value
        if (strength > 0f) drawRect(currentValueOf(LocalPullsColors).active, alpha = strength)
    }
}
