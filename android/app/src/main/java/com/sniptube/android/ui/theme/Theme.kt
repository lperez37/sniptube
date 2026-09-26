package com.sniptube.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Mauve,
    onPrimary = Ink,
    primaryContainer = SurfaceHighestDark,
    onPrimaryContainer = TextDark,
    secondary = Pink,
    secondaryContainer = SurfaceElevatedDark,
    onSecondaryContainer = TextDark,
    background = Ink,
    onBackground = TextDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceElevatedDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceElevatedDark,
    surfaceContainerHighest = SurfaceHighestDark,
    onSurface = TextDark,
    onSurfaceVariant = TextMutedDark,
    outline = OutlineDark,
    error = ErrorDark,
)

private val LightColors = lightColorScheme(
    primary = MauveDark,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFF0E0FA),
    onPrimaryContainer = TextLight,
    secondary = ColorTokens.PinkDark,
    secondaryContainer = SurfaceElevatedLight,
    onSecondaryContainer = TextLight,
    background = SurfaceLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceElevatedLight,
    onSurface = TextLight,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF5D5363),
    outline = androidx.compose.ui.graphics.Color(0xFF766B7A),
)

private val SniptubeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private object ColorTokens {
    val PinkDark = androidx.compose.ui.graphics.Color(0xFF7E526F)
}

@Composable
fun SniptubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = SniptubeShapes,
        content = content,
    )
}
