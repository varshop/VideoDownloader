package com.example.videodl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// أخضر هوية Var shop: #41B689
private val Light = lightColorScheme(
    primary = Color(0xFF2E9C70),
    onPrimary = Color.White,
    secondary = Color(0xFF41B689),
    background = Color(0xFFF5F8F7),
    surface = Color.White
)
private val Dark = darkColorScheme(
    primary = Color(0xFF41B689),
    onPrimary = Color(0xFF00281A),
    secondary = Color(0xFF7FDDB8),
    background = Color(0xFF0E1412),
    surface = Color(0xFF18201D)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
