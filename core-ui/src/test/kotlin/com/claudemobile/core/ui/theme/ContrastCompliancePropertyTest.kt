package com.claudemobile.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlin.math.pow

/**
 * Property-based test for Contrast Compliance.
 *
 * **Validates: Requirements 15.3**
 *
 * Property 18: For any theme (light/dark) text color and background color combination
 * used in the app, standard text contrast ratio is at least 4.5:1 and large text
 * contrast ratio is at least 3:1.
 *
 * Uses the WCAG 2.1 relative luminance formula:
 *   L = 0.2126*R + 0.7152*G + 0.0722*B (where R,G,B are linearized sRGB values)
 *   Contrast ratio = (L1 + 0.05) / (L2 + 0.05)
 */
@OptIn(ExperimentalKotest::class)
class ContrastCompliancePropertyTest : FunSpec({

    tags(
        Tag("Feature: android-claude-termux-client"),
        Tag("Property 18: Contrast compliance")
    )

    /**
     * Represents a text/background color pair used in the app with metadata
     * about whether it's used for standard or large text.
     */
    data class ColorPair(
        val name: String,
        val textColor: Color,
        val backgroundColor: Color,
        val isLargeText: Boolean
    )

    // Define all text/background color pairs used in the light theme
    val lightThemePairs = listOf(
        // Primary text on surfaces
        ColorPair("Light: OnBackground on Background", LightColors.OnBackground, LightColors.Background, false),
        ColorPair("Light: OnSurface on Surface", LightColors.OnSurface, LightColors.Surface, false),
        ColorPair("Light: OnSurfaceVariant on SurfaceVariant", LightColors.OnSurfaceVariant, LightColors.SurfaceVariant, false),

        // Primary color on background (used as accent text)
        ColorPair("Light: Primary on Background", LightColors.Primary, LightColors.Background, false),
        ColorPair("Light: Secondary on Background", LightColors.Secondary, LightColors.Background, false),
        ColorPair("Light: Tertiary on Background", LightColors.Tertiary, LightColors.Background, false),

        // Container text colors
        ColorPair("Light: OnPrimaryContainer on PrimaryContainer", LightColors.OnPrimaryContainer, LightColors.PrimaryContainer, false),
        ColorPair("Light: OnSecondaryContainer on SecondaryContainer", LightColors.OnSecondaryContainer, LightColors.SecondaryContainer, false),
        ColorPair("Light: OnTertiaryContainer on TertiaryContainer", LightColors.OnTertiaryContainer, LightColors.TertiaryContainer, false),
        ColorPair("Light: OnErrorContainer on ErrorContainer", LightColors.OnErrorContainer, LightColors.ErrorContainer, false),

        // On-color text (text on colored buttons/surfaces)
        ColorPair("Light: OnPrimary on Primary", LightColors.OnPrimary, LightColors.Primary, false),
        ColorPair("Light: OnSecondary on Secondary", LightColors.OnSecondary, LightColors.Secondary, false),
        ColorPair("Light: OnTertiary on Tertiary", LightColors.OnTertiary, LightColors.Tertiary, false),
        ColorPair("Light: OnError on Error", LightColors.OnError, LightColors.Error, false),

        // Error text on background
        ColorPair("Light: Error on Background", LightColors.Error, LightColors.Background, false),

        // Outline used as large text (borders, icons)
        ColorPair("Light: Outline on Background (large text)", LightColors.Outline, LightColors.Background, true),

        // Inverse colors
        ColorPair("Light: InverseOnSurface on InverseSurface", LightColors.InverseOnSurface, LightColors.InverseSurface, false),

        // Surface container text
        ColorPair("Light: OnSurface on SurfaceContainerLow", LightColors.OnSurface, LightColors.SurfaceContainerLow, false),
        ColorPair("Light: OnSurface on SurfaceContainer", LightColors.OnSurface, LightColors.SurfaceContainer, false),
        ColorPair("Light: OnSurface on SurfaceContainerHigh", LightColors.OnSurface, LightColors.SurfaceContainerHigh, false),
        ColorPair("Light: OnSurface on SurfaceContainerHighest", LightColors.OnSurface, LightColors.SurfaceContainerHighest, false)
    )

    // Define all text/background color pairs used in the dark theme
    val darkThemePairs = listOf(
        // Primary text on surfaces
        ColorPair("Dark: OnBackground on Background", DarkColors.OnBackground, DarkColors.Background, false),
        ColorPair("Dark: OnSurface on Surface", DarkColors.OnSurface, DarkColors.Surface, false),
        ColorPair("Dark: OnSurfaceVariant on SurfaceVariant", DarkColors.OnSurfaceVariant, DarkColors.SurfaceVariant, false),

        // Primary color on background (used as accent text)
        ColorPair("Dark: Primary on Background", DarkColors.Primary, DarkColors.Background, false),
        ColorPair("Dark: Secondary on Background", DarkColors.Secondary, DarkColors.Background, false),
        ColorPair("Dark: Tertiary on Background", DarkColors.Tertiary, DarkColors.Background, false),

        // Container text colors
        ColorPair("Dark: OnPrimaryContainer on PrimaryContainer", DarkColors.OnPrimaryContainer, DarkColors.PrimaryContainer, false),
        ColorPair("Dark: OnSecondaryContainer on SecondaryContainer", DarkColors.OnSecondaryContainer, DarkColors.SecondaryContainer, false),
        ColorPair("Dark: OnTertiaryContainer on TertiaryContainer", DarkColors.OnTertiaryContainer, DarkColors.TertiaryContainer, false),
        ColorPair("Dark: OnErrorContainer on ErrorContainer", DarkColors.OnErrorContainer, DarkColors.ErrorContainer, false),

        // On-color text (text on colored buttons/surfaces)
        ColorPair("Dark: OnPrimary on Primary", DarkColors.OnPrimary, DarkColors.Primary, false),
        ColorPair("Dark: OnSecondary on Secondary", DarkColors.OnSecondary, DarkColors.Secondary, false),
        ColorPair("Dark: OnTertiary on Tertiary", DarkColors.OnTertiary, DarkColors.Tertiary, false),
        ColorPair("Dark: OnError on Error", DarkColors.OnError, DarkColors.Error, false),

        // Error text on background
        ColorPair("Dark: Error on Background", DarkColors.Error, DarkColors.Background, false),

        // Outline used as large text (borders, icons)
        ColorPair("Dark: Outline on Background (large text)", DarkColors.Outline, DarkColors.Background, true),

        // Inverse colors
        ColorPair("Dark: InverseOnSurface on InverseSurface", DarkColors.InverseOnSurface, DarkColors.InverseSurface, false),

        // Surface container text
        ColorPair("Dark: OnSurface on SurfaceContainerLow", DarkColors.OnSurface, DarkColors.SurfaceContainerLow, false),
        ColorPair("Dark: OnSurface on SurfaceContainer", DarkColors.OnSurface, DarkColors.SurfaceContainer, false),
        ColorPair("Dark: OnSurface on SurfaceContainerHigh", DarkColors.OnSurface, DarkColors.SurfaceContainerHigh, false),
        ColorPair("Dark: OnSurface on SurfaceContainerHighest", DarkColors.OnSurface, DarkColors.SurfaceContainerHighest, false)
    )

    val allColorPairs = lightThemePairs + darkThemePairs

    test("Feature: android-claude-termux-client, Property 18: Contrast compliance") {
        checkAll(PropTestConfig(iterations = 100), Arb.of(allColorPairs)) { pair ->
            val ratio = wcagContrastRatio(pair.textColor, pair.backgroundColor)
            val requiredRatio = if (pair.isLargeText) 3.0 else 4.5

            ratio shouldBeGreaterThanOrEqual requiredRatio
        }
    }
})

/**
 * Calculates the WCAG 2.1 contrast ratio between two colors.
 * Returns a value between 1.0 and 21.0.
 *
 * Formula: (L1 + 0.05) / (L2 + 0.05) where L1 >= L2
 */
private fun wcagContrastRatio(foreground: Color, background: Color): Double {
    val fgLuminance = relativeLuminance(foreground)
    val bgLuminance = relativeLuminance(background)
    val lighter = maxOf(fgLuminance, bgLuminance)
    val darker = minOf(fgLuminance, bgLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Calculates the relative luminance of a color per WCAG 2.1 definition.
 * L = 0.2126*R + 0.7152*G + 0.0722*B
 * where R, G, B are linearized sRGB channel values.
 */
private fun relativeLuminance(color: Color): Double {
    val r = linearizeSrgb(color.red.toDouble())
    val g = linearizeSrgb(color.green.toDouble())
    val b = linearizeSrgb(color.blue.toDouble())
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Linearizes an sRGB color channel value (0.0 to 1.0).
 * If value <= 0.03928: linear = value / 12.92
 * Otherwise: linear = ((value + 0.055) / 1.055) ^ 2.4
 */
private fun linearizeSrgb(value: Double): Double {
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}
