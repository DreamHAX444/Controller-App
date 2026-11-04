package com.livetracker.controller.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

enum class ThemeAccentPreset(
    val label: String,
    val primaryDark: Color,
    val primaryLight: Color,
    val secondaryDark: Color,
    val secondaryLight: Color
) {
    SAPPHIRE(
        label = "Sapphire",
        primaryDark = Color(0xFF3B82F6),
        primaryLight = Color(0xFF2563EB),
        secondaryDark = Color(0xFF60A5FA),
        secondaryLight = Color(0xFF1D4ED8)
    ),
    EMERALD(
        label = "Emerald",
        primaryDark = Color(0xFF10B981),
        primaryLight = Color(0xFF059669),
        secondaryDark = Color(0xFF34D399),
        secondaryLight = Color(0xFF047857)
    ),
    VIOLET(
        label = "Violet",
        primaryDark = Color(0xFF8B5CF6),
        primaryLight = Color(0xFF7C3AED),
        secondaryDark = Color(0xFFA78BFA),
        secondaryLight = Color(0xFF6D28D9)
    ),
    AMBER(
        label = "Amber",
        primaryDark = Color(0xFFF59E0B),
        primaryLight = Color(0xFFD97706),
        secondaryDark = Color(0xFFFBBF24),
        secondaryLight = Color(0xFFB45309)
    ),
    ROSE(
        label = "Rose",
        primaryDark = Color(0xFFF43F5E),
        primaryLight = Color(0xFFE11D48),
        secondaryDark = Color(0xFFFB7185),
        secondaryLight = Color(0xFFBE123C)
    ),
    CYAN(
        label = "Cyan",
        primaryDark = Color(0xFF06B6D4),
        primaryLight = Color(0xFF0891B2),
        secondaryDark = Color(0xFF22D3EE),
        secondaryLight = Color(0xFF0E7490)
    ),
    CUSTOM(
        label = "Custom",
        primaryDark = Color(0xFF3B82F6),
        primaryLight = Color(0xFF2563EB),
        secondaryDark = Color(0xFF60A5FA),
        secondaryLight = Color(0xFF1D4ED8)
    )
}

data class ThemeConfiguration(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: ThemeAccentPreset = ThemeAccentPreset.SAPPHIRE,
    val customHueDegrees: Int = 215,
    val useDynamicSystemColor: Boolean = false
)

fun customAccentColors(hueDegrees: Int, isDark: Boolean): Pair<Color, Color> {
    val normalizedHue = ((hueDegrees % 360) + 360) % 360
    val primary = if (isDark) {
        Color.hsv(normalizedHue.toFloat(), 0.72f, 0.96f)
    } else {
        Color.hsv(normalizedHue.toFloat(), 0.82f, 0.84f)
    }
    val secondary = if (isDark) {
        Color.hsv(((normalizedHue + 16) % 360).toFloat(), 0.58f, 0.98f)
    } else {
        Color.hsv(((normalizedHue + 16) % 360).toFloat(), 0.75f, 0.72f)
    }
    return primary to secondary
}

private fun buildColorScheme(
    isDark: Boolean,
    primary: Color,
    secondary: Color
): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.22f),
            onPrimaryContainer = TraceOnPrimaryContainerDark,
            secondary = secondary,
            onSecondary = TraceOnSecondaryDark,
            secondaryContainer = secondary.copy(alpha = 0.18f),
            onSecondaryContainer = TraceOnSecondaryContainerDark,
            tertiary = StatusLive,
            onTertiary = Color(0xFF0A0E14),
            background = TraceBackgroundDark,
            onBackground = TraceOnBackgroundDark,
            surface = TraceSurfaceDark,
            onSurface = TraceOnSurfaceDark,
            surfaceVariant = TraceSurfaceVariantDark,
            onSurfaceVariant = TraceOnSurfaceVariantDark,
            outline = TraceOutlineDark,
            outlineVariant = TraceOutlineVariantDark,
            error = StatusError,
            onError = Color.White,
            errorContainer = Color(0xFF3D1418),
            onErrorContainer = Color(0xFFFFB4AB)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.14f),
            onPrimaryContainer = TraceOnPrimaryContainerLight,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondary.copy(alpha = 0.12f),
            onSecondaryContainer = TraceOnSecondaryContainerLight,
            tertiary = Color(0xFF059669),
            onTertiary = Color.White,
            background = TraceBackgroundLight,
            onBackground = TraceOnBackgroundLight,
            surface = TraceSurfaceLight,
            onSurface = TraceOnSurfaceLight,
            surfaceVariant = TraceSurfaceVariantLight,
            onSurfaceVariant = TraceOnSurfaceVariantLight,
            outline = TraceOutlineLight,
            outlineVariant = TraceOutlineVariantLight,
            error = StatusError,
            onError = Color.White,
            errorContainer = StatusErrorContainer,
            onErrorContainer = StatusOnErrorContainer
        )
    }
}

@Composable
fun TraceTheme(
    darkTheme: Boolean? = null,
    themeConfig: ThemeConfiguration = ThemeConfiguration(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = darkTheme ?: when (themeConfig.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val context = LocalContext.current
    val baseScheme = if (themeConfig.useDynamicSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val (primary, secondary) = if (themeConfig.accentPreset == ThemeAccentPreset.CUSTOM) {
            customAccentColors(themeConfig.customHueDegrees, effectiveDark)
        } else {
            val p = if (effectiveDark) themeConfig.accentPreset.primaryDark else themeConfig.accentPreset.primaryLight
            val s = if (effectiveDark) themeConfig.accentPreset.secondaryDark else themeConfig.accentPreset.secondaryLight
            p to s
        }
        buildColorScheme(effectiveDark, primary, secondary)
    }

    val animatedPrimary by animateColorAsState(baseScheme.primary, tween(240), label = "themePrimary")
    val animatedSecondary by animateColorAsState(baseScheme.secondary, tween(240), label = "themeSecondary")
    val animatedBackground by animateColorAsState(baseScheme.background, tween(240), label = "themeBg")
    val animatedSurface by animateColorAsState(baseScheme.surface, tween(240), label = "themeSurface")
    val animatedSurfaceVariant by animateColorAsState(baseScheme.surfaceVariant, tween(240), label = "themeSurfaceVar")

    val colorScheme = baseScheme.copy(
        primary = animatedPrimary,
        secondary = animatedSecondary,
        background = animatedBackground,
        surface = animatedSurface,
        surfaceVariant = animatedSurfaceVariant
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !effectiveDark
                insetsController.isAppearanceLightNavigationBars = !effectiveDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
