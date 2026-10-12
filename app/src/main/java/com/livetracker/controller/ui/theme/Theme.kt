package com.livetracker.controller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = TracePrimaryDark,
    onPrimary = TraceOnPrimaryDark,
    primaryContainer = TracePrimaryContainerDark,
    onPrimaryContainer = TraceOnPrimaryContainerDark,
    secondary = TraceSecondaryDark,
    onSecondary = TraceOnSecondaryDark,
    secondaryContainer = TraceSecondaryContainerDark,
    onSecondaryContainer = TraceOnSecondaryContainerDark,
    background = TraceBackgroundDark,
    onBackground = TraceOnBackgroundDark,
    surface = TraceSurfaceDark,
    onSurface = TraceOnSurfaceDark,
    surfaceVariant = TraceSurfaceVariantDark,
    onSurfaceVariant = TraceOnSurfaceVariantDark,
    outline = TraceOutlineDark,
    outlineVariant = TraceOutlineVariantDark,
    error = StatusError,
    onError = TraceOnPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = TracePrimaryLight,
    onPrimary = TraceOnPrimaryLight,
    primaryContainer = TracePrimaryContainerLight,
    onPrimaryContainer = TraceOnPrimaryContainerLight,
    secondary = TraceSecondaryLight,
    onSecondary = TraceOnSecondaryLight,
    secondaryContainer = TraceSecondaryContainerLight,
    onSecondaryContainer = TraceOnSecondaryContainerLight,
    background = TraceBackgroundLight,
    onBackground = TraceOnBackgroundLight,
    surface = TraceSurfaceLight,
    onSurface = TraceOnSurfaceLight,
    surfaceVariant = TraceSurfaceVariantLight,
    onSurfaceVariant = TraceOnSurfaceVariantLight,
    outline = TraceOutlineLight,
    outlineVariant = TraceOutlineVariantLight,
    error = StatusError,
    onError = TraceOnPrimaryLight
)

@Composable
fun TraceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
