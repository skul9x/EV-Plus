package com.evcs.favorites.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val CarDarkColorScheme = darkColorScheme(
    primary = CarAccentGreen,
    onPrimary = Color.Black,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = CarAccentGreen,
    secondary = CarAccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = ElectricCyanContainerDark,
    onSecondaryContainer = CarAccentCyan,
    tertiary = UltraPurple,
    onTertiary = Color.White,
    tertiaryContainer = UltraPurpleContainerDark,
    onTertiaryContainer = UltraPurpleLight,
    background = CarDarkBackground,
    onBackground = CarTextPrimary,
    surface = CarDarkSurface,
    onSurface = CarTextPrimary,
    surfaceVariant = CarDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = CarDarkOutline,
    outlineVariant = CarDarkOutline
)

val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldOnContainerDark,
    secondary = ElectricCyan,
    onSecondary = Color.Black,
    secondaryContainer = ElectricCyanContainerDark,
    onSecondaryContainer = ElectricCyanLight,
    tertiary = UltraPurple,
    onTertiary = Color.White,
    tertiaryContainer = UltraPurpleContainerDark,
    onTertiaryContainer = UltraPurpleLight,
    background = CarDarkBackground,
    onBackground = CarTextPrimary,
    surface = CarDarkSurface,
    onSurface = CarTextPrimary,
    surfaceVariant = CarDarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = CarDarkOutline,
    outlineVariant = DarkOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = EmeraldOnContainerLight,
    secondary = ElectricCyanDark,
    onSecondary = Color.White,
    secondaryContainer = ElectricCyanContainerLight,
    onSecondaryContainer = ElectricCyanDark,
    tertiary = UltraPurple,
    onTertiary = Color.White,
    tertiaryContainer = UltraPurpleContainerLight,
    onTertiaryContainer = UltraPurple,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

@Composable
fun EvcsFavoritesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isCarMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        isCarMode -> CarDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !isCarMode
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
