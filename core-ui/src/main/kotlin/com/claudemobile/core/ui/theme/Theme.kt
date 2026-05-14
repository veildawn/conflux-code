package com.claudemobile.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.claudemobile.core.domain.model.ThemeMode

/**
 * Light color scheme with colors meeting WCAG AA 4.5:1 contrast ratio.
 */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightColors.Primary,
    onPrimary = LightColors.OnPrimary,
    primaryContainer = LightColors.PrimaryContainer,
    onPrimaryContainer = LightColors.OnPrimaryContainer,
    secondary = LightColors.Secondary,
    onSecondary = LightColors.OnSecondary,
    secondaryContainer = LightColors.SecondaryContainer,
    onSecondaryContainer = LightColors.OnSecondaryContainer,
    tertiary = LightColors.Tertiary,
    onTertiary = LightColors.OnTertiary,
    tertiaryContainer = LightColors.TertiaryContainer,
    onTertiaryContainer = LightColors.OnTertiaryContainer,
    error = LightColors.Error,
    onError = LightColors.OnError,
    errorContainer = LightColors.ErrorContainer,
    onErrorContainer = LightColors.OnErrorContainer,
    background = LightColors.Background,
    onBackground = LightColors.OnBackground,
    surface = LightColors.Surface,
    onSurface = LightColors.OnSurface,
    surfaceVariant = LightColors.SurfaceVariant,
    onSurfaceVariant = LightColors.OnSurfaceVariant,
    outline = LightColors.Outline,
    outlineVariant = LightColors.OutlineVariant,
    inverseSurface = LightColors.InverseSurface,
    inverseOnSurface = LightColors.InverseOnSurface,
    inversePrimary = LightColors.InversePrimary,
    scrim = LightColors.Scrim,
    surfaceTint = LightColors.SurfaceTint,
    surfaceDim = LightColors.SurfaceDim,
    surfaceBright = LightColors.SurfaceBright,
    surfaceContainerLowest = LightColors.SurfaceContainerLowest,
    surfaceContainerLow = LightColors.SurfaceContainerLow,
    surfaceContainer = LightColors.SurfaceContainer,
    surfaceContainerHigh = LightColors.SurfaceContainerHigh,
    surfaceContainerHighest = LightColors.SurfaceContainerHighest
)

/**
 * Dark color scheme with colors meeting WCAG AA 4.5:1 contrast ratio.
 */
private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkColors.Primary,
    onPrimary = DarkColors.OnPrimary,
    primaryContainer = DarkColors.PrimaryContainer,
    onPrimaryContainer = DarkColors.OnPrimaryContainer,
    secondary = DarkColors.Secondary,
    onSecondary = DarkColors.OnSecondary,
    secondaryContainer = DarkColors.SecondaryContainer,
    onSecondaryContainer = DarkColors.OnSecondaryContainer,
    tertiary = DarkColors.Tertiary,
    onTertiary = DarkColors.OnTertiary,
    tertiaryContainer = DarkColors.TertiaryContainer,
    onTertiaryContainer = DarkColors.OnTertiaryContainer,
    error = DarkColors.Error,
    onError = DarkColors.OnError,
    errorContainer = DarkColors.ErrorContainer,
    onErrorContainer = DarkColors.OnErrorContainer,
    background = DarkColors.Background,
    onBackground = DarkColors.OnBackground,
    surface = DarkColors.Surface,
    onSurface = DarkColors.OnSurface,
    surfaceVariant = DarkColors.SurfaceVariant,
    onSurfaceVariant = DarkColors.OnSurfaceVariant,
    outline = DarkColors.Outline,
    outlineVariant = DarkColors.OutlineVariant,
    inverseSurface = DarkColors.InverseSurface,
    inverseOnSurface = DarkColors.InverseOnSurface,
    inversePrimary = DarkColors.InversePrimary,
    scrim = DarkColors.Scrim,
    surfaceTint = DarkColors.SurfaceTint,
    surfaceDim = DarkColors.SurfaceDim,
    surfaceBright = DarkColors.SurfaceBright,
    surfaceContainerLowest = DarkColors.SurfaceContainerLowest,
    surfaceContainerLow = DarkColors.SurfaceContainerLow,
    surfaceContainer = DarkColors.SurfaceContainer,
    surfaceContainerHigh = DarkColors.SurfaceContainerHigh,
    surfaceContainerHighest = DarkColors.SurfaceContainerHighest
)

/**
 * The main application theme composable.
 *
 * Applies Material 3 theming with light/dark color schemes based on the [themeMode] preference.
 * On Android 12+ (API 31+), dynamic color is supported and used when [dynamicColor] is true.
 *
 * Provides [Spacing] and [Elevation] design tokens via CompositionLocals.
 *
 * @param themeMode The user's theme preference (system/light/dark). Maps to the [ThemeMode] enum.
 * @param dynamicColor Whether to use Android 12+ dynamic color. Defaults to false for consistent branding.
 * @param content The composable content to render within this theme.
 */
@Composable
public fun ClaudeMobileTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalElevation provides Elevation()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
