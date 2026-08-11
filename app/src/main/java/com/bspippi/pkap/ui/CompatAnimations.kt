package com.bspippi.pkap.ui

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compatibility helper for the Compose animation version used by PKap.
 * Keeps the call sites readable while animating the Dp value through Float.
 */
@Composable
fun InfiniteTransition.animateDp(
    initialValue: Dp,
    targetValue: Dp,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String = "DpAnimation"
): State<Dp> {
    val value = animateFloat(
        initialValue = initialValue.value,
        targetValue = targetValue.value,
        animationSpec = animationSpec,
        label = label
    )
    return derivedStateOf { value.value.dp }
}
