package com.example.videodl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    secondary = Color(0xFF00A99D),
    background = Color(0xFFF6F6FB),
    surface = Color.White
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9D92FF),
    secondary = Color(0xFF3DDAD0),
    background = Color(0xFF0F0F14),
    surface = Color(0xFF1A1A22)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
