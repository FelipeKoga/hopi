package dev.koga.hopi.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Material 3 Expressive's motion physics (springs only, no durations): spatial springs move
 * position, size and shape with a hint of overshoot; effects springs fade color and alpha with
 * none. The values are M3 Expressive's "standard" scheme, calm enough for a work tool.
 */
internal object HopiMotion {
    /** Small components: a press, a chip, a switch, a corner morph. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** Larger moves: a panel sliding in, a tab's content, list items. */
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** Color and alpha on small components: hover, selection. */
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    /** Color and alpha on larger surfaces: a selected card, a panel's fade. */
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}
