package io.github.eggplants.maydo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** The theme colour. The schemes below are Material's Neutral scheme generated from it. */
val Seed = Color(0xFFF5F6F6)

val LightColors =
    lightColorScheme(
        primary = Color(0xFF576061),
        onPrimary = Color(0xFFF0F9FB),
        primaryContainer = Color(0xFFDAE4E6),
        onPrimaryContainer = Color(0xFF4A5355),
        inversePrimary = Color(0xFFBEC8CA),
        secondary = Color(0xFF5C6060),
        onSecondary = Color(0xFFF7F9F9),
        secondaryContainer = Color(0xFFE0E3E3),
        onSecondaryContainer = Color(0xFF4F5253),
        tertiary = Color(0xFF476371),
        onTertiary = Color(0xFFF3FAFF),
        tertiaryContainer = Color(0xFFCFECFE),
        onTertiaryContainer = Color(0xFF3D5967),
        // The seed itself is the backdrop; containers step darker from it and cards lift to white.
        background = Seed,
        onBackground = Color(0xFF2D3131),
        surface = Seed,
        onSurface = Color(0xFF2D3131),
        surfaceVariant = Color(0xFFDFE3E3),
        onSurfaceVariant = Color(0xFF5A5F5F),
        surfaceTint = Color(0xFF576061),
        inverseSurface = Color(0xFF0E0F0F),
        inverseOnSurface = Color(0xFF9D9E9E),
        error = Color(0xFF9F403D),
        onError = Color(0xFFFFF7F6),
        errorContainer = Color(0xFFFE8983),
        onErrorContainer = Color(0xFF752121),
        outline = Color(0xFF767B7B),
        outlineVariant = Color(0xFFADB2B2),
        scrim = Color(0xFF000000),
        surfaceBright = Seed,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEFF1F1),
        surfaceContainer = Color(0xFFE9ECEC),
        surfaceContainerHigh = Color(0xFFE3E6E6),
        surfaceContainerHighest = Color(0xFFDDE1E1),
        surfaceDim = Color(0xFFD5D9D9),
    )

val DarkColors =
    darkColorScheme(
        primary = Color(0xFFBEC8CA),
        onPrimary = Color(0xFF384243),
        primaryContainer = Color(0xFF3F484A),
        onPrimaryContainer = Color(0xFFC8D2D3),
        inversePrimary = Color(0xFF576162),
        secondary = Color(0xFFB9BDBD),
        onSecondary = Color(0xFF1D2121),
        secondaryContainer = Color(0xFF393C3C),
        onSecondaryContainer = Color(0xFFBDC0C0),
        tertiary = Color(0xFFB3CEDF),
        onTertiary = Color(0xFF1C3441),
        tertiaryContainer = Color(0xFF334B58),
        onTertiaryContainer = Color(0xFFCFECFE),
        background = Color(0xFF0F1111),
        onBackground = Color(0xFFE3E5E5),
        surface = Color(0xFF0F1111),
        onSurface = Color(0xFFE3E5E5),
        surfaceVariant = Color(0xFF3F4444),
        onSurfaceVariant = Color(0xFFBEC3C3),
        surfaceTint = Color(0xFFBEC8CA),
        inverseSurface = Color(0xFFF5F6F6),
        inverseOnSurface = Color(0xFF2D3131),
        error = Color(0xFFEE7D77),
        onError = Color(0xFF490106),
        errorContainer = Color(0xFF7F2927),
        onErrorContainer = Color(0xFFFF9993),
        outline = Color(0xFF888D8D),
        outlineVariant = Color(0xFF3F4444),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF353939),
        surfaceContainerLowest = Color(0xFF0A0C0C),
        surfaceContainerLow = Color(0xFF171A1A),
        surfaceContainer = Color(0xFF1B1E1E),
        surfaceContainerHigh = Color(0xFF252828),
        surfaceContainerHighest = Color(0xFF303333),
        surfaceDim = Color(0xFF0F1111),
    )
