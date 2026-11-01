package dev.lelonio.square.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The Android app's dark palette: near-black floor, off-white ink, violet accent. */
object SquareColors {
    val Floor = Color(0xFF0D0E11)
    val Ink = Color(0xFFF7F8FA)
    val InkSoft = Color(0xB3F7F8FA)
    val InkFaint = Color(0x73F7F8FA)
    val Accent = Color(0xFF7C5CE6)
    val AccentSoft = Color(0xFF9C84F0)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val GlassEdge = Color(0x1FFFFFFF)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun SquareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SquareColors.Accent,
            onPrimary = Color.White,
            secondary = SquareColors.AccentSoft,
            background = SquareColors.Floor,
            onBackground = SquareColors.Ink,
            surface = Color(0xFF16171B),
            onSurface = SquareColors.Ink,
            surfaceVariant = Color(0xFF1E2025),
            onSurfaceVariant = SquareColors.InkSoft,
            outline = SquareColors.GlassEdge,
            error = SquareColors.Danger,
        ),
        typography = Typography(
            displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp),
            bodyMedium = TextStyle(fontSize = 13.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}
