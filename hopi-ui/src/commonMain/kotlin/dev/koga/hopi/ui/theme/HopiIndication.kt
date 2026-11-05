package dev.koga.hopi.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * Hover, press and keyboard-focus feedback: [color] over the control while the pointer is on it or
 * it has focus, twice its alpha while pressed. Drawn only: hovering recomposes nothing (list rows
 * included). A click focuses a control too, but Compose for desktop passes an indication only the
 * focus that came from the keyboard, so a clicked control is not left lit.
 */
internal class HopiIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = OverlayNode(interactionSource, color)

    override fun equals(other: Any?): Boolean = other is HopiIndication && other.color == color

    override fun hashCode(): Int = color.hashCode()
}

private class OverlayNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private var hovers = 0
    private var presses = 0
    private var focuses = 0

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovers++
                    // Never below zero: a node made while the pointer was already on the control
                    // (a new theme's) sees an exit without its enter.
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses = (focuses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    // The exit, release or unfocus a control sends as it leaves reaches no collector once this
    // node's scope is cancelled: start clean if the node comes back (a reused list row).
    override fun onDetach() {
        hovers = 0
        presses = 0
        focuses = 0
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = when {
            presses > 0 -> color.alpha * 2f
            hovers > 0 || focuses > 0 -> color.alpha
            else -> return
        }
        drawRect(color.copy(alpha = alpha.coerceAtMost(1f)))
    }
}
