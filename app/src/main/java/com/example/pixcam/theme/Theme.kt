package com.example.pixcam.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// a camera app is always dark; wallpaper-derived dynamic color is deliberately off
private val PixColorScheme = darkColorScheme(
  primary = PixAccent,
  onPrimary = PixBackground,
  secondary = PixAccent,
  onSecondary = PixBackground,
  background = PixBackground,
  onBackground = PixOnDark,
  surface = PixSurface,
  onSurface = PixOnDark,
  surfaceVariant = PixSurfaceHigh,
  onSurfaceVariant = PixDim,
)

@Composable
fun PixcamTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = PixColorScheme, typography = Typography, content = content)
}
