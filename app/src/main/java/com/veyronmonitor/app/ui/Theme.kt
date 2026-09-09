package com.veyronmonitor.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SunYellow = Color(0xFFFACC15)
private val DeepNavy = Color(0xFF0F172A)
private val SolarGreen = Color(0xFF22C55E)

private val DarkColors = darkColorScheme(
    primary = SunYellow,
    secondary = SolarGreen,
    background = DeepNavy,
    surface = Color(0xFF1E293B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45309),
    secondary = Color(0xFF16A34A),
    background = Color(0xFFF8FAFC),
    surface = Color.White
)

@Composable
fun VeyronMonitorTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
