package com.claudemobile.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens for spacing used throughout the app.
 * Provides a consistent spacing scale based on a 4dp grid.
 */
@Immutable
public data class Spacing(
    /** 2dp - Minimal spacing for tight layouts */
    val xxs: Dp = 2.dp,
    /** 4dp - Extra small spacing */
    val xs: Dp = 4.dp,
    /** 8dp - Small spacing between related elements */
    val sm: Dp = 8.dp,
    /** 12dp - Medium-small spacing */
    val md: Dp = 12.dp,
    /** 16dp - Standard spacing between sections */
    val lg: Dp = 16.dp,
    /** 24dp - Large spacing for visual separation */
    val xl: Dp = 24.dp,
    /** 32dp - Extra large spacing */
    val xxl: Dp = 32.dp,
    /** 48dp - Maximum spacing for major sections */
    val xxxl: Dp = 48.dp
)

/**
 * Design tokens for elevation used throughout the app.
 * Follows Material 3 elevation levels.
 */
@Immutable
public data class Elevation(
    /** 0dp - No elevation (flat surface) */
    val level0: Dp = 0.dp,
    /** 1dp - Lowest elevation (cards, navigation) */
    val level1: Dp = 1.dp,
    /** 3dp - Low elevation (elevated cards) */
    val level2: Dp = 3.dp,
    /** 6dp - Medium elevation (floating action buttons) */
    val level3: Dp = 6.dp,
    /** 8dp - High elevation (navigation drawers) */
    val level4: Dp = 8.dp,
    /** 12dp - Highest elevation (dialogs, modals) */
    val level5: Dp = 12.dp
)

/**
 * CompositionLocal for accessing spacing tokens within the composition tree.
 */
public val LocalSpacing: androidx.compose.runtime.ProvidableCompositionLocal<Spacing> =
    staticCompositionLocalOf { Spacing() }

/**
 * CompositionLocal for accessing elevation tokens within the composition tree.
 */
public val LocalElevation: androidx.compose.runtime.ProvidableCompositionLocal<Elevation> =
    staticCompositionLocalOf { Elevation() }
