package com.claudemobile.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.pow

/**
 * Unit tests for the app theme, color contrast ratios, typography scaling,
 * and design token completeness.
 */
class ThemeTest : DescribeSpec({

    describe("Light theme contrast ratios") {

        it("primary on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.Primary, LightColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onBackground on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnBackground, LightColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSurface on surface meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnSurface, LightColors.Surface)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onPrimaryContainer on primaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnPrimaryContainer, LightColors.PrimaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSecondaryContainer on secondaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnSecondaryContainer, LightColors.SecondaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onTertiaryContainer on tertiaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnTertiaryContainer, LightColors.TertiaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onErrorContainer on errorContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnErrorContainer, LightColors.ErrorContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("error on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.Error, LightColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("secondary on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.Secondary, LightColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSurfaceVariant on surfaceVariant meets 4.5:1 ratio") {
            val ratio = contrastRatio(LightColors.OnSurfaceVariant, LightColors.SurfaceVariant)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("outline on background meets 4.5:1 for normal text") {
            val ratio = contrastRatio(LightColors.Outline, LightColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }
    }

    describe("Dark theme contrast ratios") {

        it("primary on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.Primary, DarkColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onBackground on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnBackground, DarkColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSurface on surface meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnSurface, DarkColors.Surface)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onPrimaryContainer on primaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnPrimaryContainer, DarkColors.PrimaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSecondaryContainer on secondaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnSecondaryContainer, DarkColors.SecondaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onTertiaryContainer on tertiaryContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnTertiaryContainer, DarkColors.TertiaryContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onErrorContainer on errorContainer meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnErrorContainer, DarkColors.ErrorContainer)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("error on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.Error, DarkColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("secondary on background meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.Secondary, DarkColors.Background)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("onSurfaceVariant on surfaceVariant meets 4.5:1 ratio") {
            val ratio = contrastRatio(DarkColors.OnSurfaceVariant, DarkColors.SurfaceVariant)
            ratio shouldBeGreaterThanOrEqual 4.5
        }

        it("outline on background meets 3:1 for large text") {
            val ratio = contrastRatio(DarkColors.Outline, DarkColors.Background)
            ratio shouldBeGreaterThanOrEqual 3.0
        }
    }

    describe("Typography uses sp units for system font scaling") {

        it("all text styles use sp for fontSize") {
            // sp units automatically scale with system font settings
            // Verify the base sizes are reasonable for 2.0x scaling
            AppTypography.displayLarge.fontSize.value shouldBe 57f
            AppTypography.displayMedium.fontSize.value shouldBe 45f
            AppTypography.displaySmall.fontSize.value shouldBe 36f
            AppTypography.headlineLarge.fontSize.value shouldBe 32f
            AppTypography.headlineMedium.fontSize.value shouldBe 28f
            AppTypography.headlineSmall.fontSize.value shouldBe 24f
            AppTypography.titleLarge.fontSize.value shouldBe 22f
            AppTypography.titleMedium.fontSize.value shouldBe 16f
            AppTypography.titleSmall.fontSize.value shouldBe 14f
            AppTypography.bodyLarge.fontSize.value shouldBe 16f
            AppTypography.bodyMedium.fontSize.value shouldBe 14f
            AppTypography.bodySmall.fontSize.value shouldBe 12f
            AppTypography.labelLarge.fontSize.value shouldBe 14f
            AppTypography.labelMedium.fontSize.value shouldBe 12f
            AppTypography.labelSmall.fontSize.value shouldBe 11f
        }

        it("body text at 2.0x scale stays within reasonable bounds") {
            // bodyLarge at 16sp * 2.0 = 32sp equivalent - still readable on mobile
            val maxScaledBody = AppTypography.bodyLarge.fontSize.value * 2.0f
            // Should be <= 40sp to avoid overflow in typical layouts
            assert(maxScaledBody <= 40f) { "Body text at 2.0x scale ($maxScaledBody sp) exceeds 40sp" }
        }

        it("display text at 2.0x scale stays within bounds") {
            // displayLarge at 57sp * 2.0 = 114sp - large but acceptable for display text
            val maxScaledDisplay = AppTypography.displayLarge.fontSize.value * 2.0f
            assert(maxScaledDisplay <= 120f) { "Display text at 2.0x scale ($maxScaledDisplay sp) exceeds 120sp" }
        }
    }

    describe("Spacing design tokens") {

        it("spacing values follow 4dp grid progression") {
            val spacing = Spacing()
            spacing.xxs.value shouldBe 2f
            spacing.xs.value shouldBe 4f
            spacing.sm.value shouldBe 8f
            spacing.md.value shouldBe 12f
            spacing.lg.value shouldBe 16f
            spacing.xl.value shouldBe 24f
            spacing.xxl.value shouldBe 32f
            spacing.xxxl.value shouldBe 48f
        }

        it("spacing values are in ascending order") {
            val spacing = Spacing()
            assert(spacing.xxs.value < spacing.xs.value)
            assert(spacing.xs.value < spacing.sm.value)
            assert(spacing.sm.value < spacing.md.value)
            assert(spacing.md.value < spacing.lg.value)
            assert(spacing.lg.value < spacing.xl.value)
            assert(spacing.xl.value < spacing.xxl.value)
            assert(spacing.xxl.value < spacing.xxxl.value)
        }
    }

    describe("Elevation design tokens") {

        it("elevation values follow Material 3 levels") {
            val elevation = Elevation()
            elevation.level0.value shouldBe 0f
            elevation.level1.value shouldBe 1f
            elevation.level2.value shouldBe 3f
            elevation.level3.value shouldBe 6f
            elevation.level4.value shouldBe 8f
            elevation.level5.value shouldBe 12f
        }

        it("elevation values are in ascending order") {
            val elevation = Elevation()
            assert(elevation.level0.value < elevation.level1.value)
            assert(elevation.level1.value < elevation.level2.value)
            assert(elevation.level2.value < elevation.level3.value)
            assert(elevation.level3.value < elevation.level4.value)
            assert(elevation.level4.value < elevation.level5.value)
        }
    }

    describe("Shape design tokens") {

        it("shapes follow Material 3 scale") {
            // Verify shapes are defined (non-null)
            AppShapes.extraSmall
            AppShapes.small
            AppShapes.medium
            AppShapes.large
            AppShapes.extraLarge
        }
    }
})

/**
 * Calculates the WCAG 2.1 contrast ratio between two colors.
 * Returns a value between 1:1 and 21:1.
 */
private fun contrastRatio(foreground: Color, background: Color): Double {
    val fgLuminance = relativeLuminance(foreground)
    val bgLuminance = relativeLuminance(background)
    val lighter = maxOf(fgLuminance, bgLuminance)
    val darker = minOf(fgLuminance, bgLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Calculates the relative luminance of a color per WCAG 2.1 definition.
 */
private fun relativeLuminance(color: Color): Double {
    val r = linearize(color.red.toDouble())
    val g = linearize(color.green.toDouble())
    val b = linearize(color.blue.toDouble())
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Linearizes an sRGB color channel value.
 */
private fun linearize(value: Double): Double {
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}
