package com.example.ppo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PPOColorScheme = lightColorScheme(
    primary             = DustyRose,
    onPrimary           = Color.White,
    primaryContainer    = DustyRoseContainer,
    onPrimaryContainer  = DustyRoseInk,

    secondary             = WarmTan,
    onSecondary           = Color.White,
    secondaryContainer    = WarmTanContainer,
    onSecondaryContainer  = WarmTanInk,

    tertiary             = Terracotta,
    onTertiary           = Color.White,
    tertiaryContainer    = TerracottaContainer,
    onTertiaryContainer  = TerracottaInk,

    background    = Parchment,
    onBackground  = WarmInk,
    surface       = ParchmentSurface,
    onSurface     = WarmInk,

    surfaceVariant   = ParchmentVariant,
    onSurfaceVariant = WarmInkMuted,
    outline          = WarmOutline,
)

@Composable
fun PPOTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PPOColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = PPOColorScheme,
        typography  = Typography,
        content     = content,
    )
}
