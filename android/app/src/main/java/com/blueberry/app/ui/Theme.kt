package com.blueberry.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Палитра «черничная»: синий с фиолетовым оттенком ягоды и зелёный акцент листика.
private val Light = lightColorScheme(
    primary = Color(0xFF3B4CC0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E3FF), onPrimaryContainer = Color(0xFF0A1370),
    secondary = Color(0xFF5A5D7E), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE0FF), onSecondaryContainer = Color(0xFF171A37),
    tertiary = Color(0xFF1F7A4D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA8F2C8), onTertiaryContainer = Color(0xFF002112),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4), surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08157A),
    primaryContainer = Color(0xFF2433A8), onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC3C4EB), onSecondary = Color(0xFF2C2F4D),
    secondaryContainer = Color(0xFF424465), onSecondaryContainer = Color(0xFFDFE0FF),
    tertiary = Color(0xFF8DD8AB), onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF00522F), onTertiaryContainer = Color(0xFFA8F2C8),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

private val BlueberryShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Единый фирменный вид на любой версии Android (динамические цвета обоев не используются). */
@Composable
fun BlueberryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = BlueberryShapes,
        content = content,
    )
}
