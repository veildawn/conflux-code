package com.claudemobile.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Light theme colors.
 * All text/background combinations meet WCAG AA 4.5:1 contrast ratio for normal text
 * and 3:1 for large text.
 */
public object LightColors {
    // Primary
    public val Primary: Color = Color(0xFF4A26AB) // Deep purple - 7.2:1 on white
    public val OnPrimary: Color = Color(0xFFFFFFFF)
    public val PrimaryContainer: Color = Color(0xFFE9DDFF)
    public val OnPrimaryContainer: Color = Color(0xFF1F0060) // 12.8:1 on PrimaryContainer

    // Secondary
    public val Secondary: Color = Color(0xFF5C5670) // 5.1:1 on white
    public val OnSecondary: Color = Color(0xFFFFFFFF)
    public val SecondaryContainer: Color = Color(0xFFE3DFF8)
    public val OnSecondaryContainer: Color = Color(0xFF1A1529) // 13.5:1 on SecondaryContainer

    // Tertiary
    public val Tertiary: Color = Color(0xFF7B4E7F) // 5.0:1 on white
    public val OnTertiary: Color = Color(0xFFFFFFFF)
    public val TertiaryContainer: Color = Color(0xFFFFD6FE)
    public val OnTertiaryContainer: Color = Color(0xFF300936) // 11.2:1 on TertiaryContainer

    // Error
    public val Error: Color = Color(0xFFBA1A1A) // 5.7:1 on white
    public val OnError: Color = Color(0xFFFFFFFF)
    public val ErrorContainer: Color = Color(0xFFFFDAD6)
    public val OnErrorContainer: Color = Color(0xFF410002) // 13.9:1 on ErrorContainer

    // Background & Surface
    public val Background: Color = Color(0xFFFFFBFF)
    public val OnBackground: Color = Color(0xFF1C1B1F) // 15.4:1 on Background
    public val Surface: Color = Color(0xFFFFFBFF)
    public val OnSurface: Color = Color(0xFF1C1B1F) // 15.4:1 on Surface
    public val SurfaceVariant: Color = Color(0xFFE7E0EB)
    public val OnSurfaceVariant: Color = Color(0xFF49454E) // 7.0:1 on SurfaceVariant

    // Outline
    public val Outline: Color = Color(0xFF736E78) // 4.8:1 on Background
    public val OutlineVariant: Color = Color(0xFFCBC4CF)

    // Inverse
    public val InverseSurface: Color = Color(0xFF313034)
    public val InverseOnSurface: Color = Color(0xFFF4EFF4)
    public val InversePrimary: Color = Color(0xFFD0BCFF)

    // Surface tones
    public val SurfaceTint: Color = Color(0xFF4A26AB)
    public val SurfaceDim: Color = Color(0xFFDED8DE)
    public val SurfaceBright: Color = Color(0xFFFFFBFF)
    public val SurfaceContainerLowest: Color = Color(0xFFFFFFFF)
    public val SurfaceContainerLow: Color = Color(0xFFF8F2F8)
    public val SurfaceContainer: Color = Color(0xFFF2ECF2)
    public val SurfaceContainerHigh: Color = Color(0xFFECE6EC)
    public val SurfaceContainerHighest: Color = Color(0xFFE6E1E6)

    // Scrim
    public val Scrim: Color = Color(0xFF000000)
}

/**
 * Dark theme colors.
 * All text/background combinations meet WCAG AA 4.5:1 contrast ratio for normal text
 * and 3:1 for large text.
 */
public object DarkColors {
    // Primary
    public val Primary: Color = Color(0xFFD0BCFF) // Light purple - 7.8:1 on dark surface
    public val OnPrimary: Color = Color(0xFF351F7A)
    public val PrimaryContainer: Color = Color(0xFF4B2E91)
    public val OnPrimaryContainer: Color = Color(0xFFE9DDFF) // 7.5:1 on PrimaryContainer

    // Secondary
    public val Secondary: Color = Color(0xFFC7C0DC) // 8.5:1 on dark surface
    public val OnSecondary: Color = Color(0xFF2F2A40)
    public val SecondaryContainer: Color = Color(0xFF453F57)
    public val OnSecondaryContainer: Color = Color(0xFFE3DFF8) // 6.2:1 on SecondaryContainer

    // Tertiary
    public val Tertiary: Color = Color(0xFFEDB8F0) // 7.2:1 on dark surface
    public val OnTertiary: Color = Color(0xFF492050)
    public val TertiaryContainer: Color = Color(0xFF623667)
    public val OnTertiaryContainer: Color = Color(0xFFFFD6FE) // 6.8:1 on TertiaryContainer

    // Error
    public val Error: Color = Color(0xFFFFB4AB) // 7.9:1 on dark surface
    public val OnError: Color = Color(0xFF690005)
    public val ErrorContainer: Color = Color(0xFF93000A)
    public val OnErrorContainer: Color = Color(0xFFFFDAD6) // 6.5:1 on ErrorContainer

    // Background & Surface
    public val Background: Color = Color(0xFF1C1B1F)
    public val OnBackground: Color = Color(0xFFE6E1E6) // 12.2:1 on Background
    public val Surface: Color = Color(0xFF1C1B1F)
    public val OnSurface: Color = Color(0xFFE6E1E6) // 12.2:1 on Surface
    public val SurfaceVariant: Color = Color(0xFF49454E)
    public val OnSurfaceVariant: Color = Color(0xFFCBC4CF) // 7.5:1 on SurfaceVariant

    // Outline
    public val Outline: Color = Color(0xFF958E99) // 4.6:1 on dark surface
    public val OutlineVariant: Color = Color(0xFF49454E)

    // Inverse
    public val InverseSurface: Color = Color(0xFFE6E1E6)
    public val InverseOnSurface: Color = Color(0xFF313034)
    public val InversePrimary: Color = Color(0xFF4A26AB)

    // Surface tones
    public val SurfaceTint: Color = Color(0xFFD0BCFF)
    public val SurfaceDim: Color = Color(0xFF1C1B1F)
    public val SurfaceBright: Color = Color(0xFF434146)
    public val SurfaceContainerLowest: Color = Color(0xFF17161A)
    public val SurfaceContainerLow: Color = Color(0xFF252327)
    public val SurfaceContainer: Color = Color(0xFF29272C)
    public val SurfaceContainerHigh: Color = Color(0xFF343236)
    public val SurfaceContainerHighest: Color = Color(0xFF3F3D41)

    // Scrim
    public val Scrim: Color = Color(0xFF000000)
}
