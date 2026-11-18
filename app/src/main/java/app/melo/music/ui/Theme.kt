package app.melo.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import app.melo.music.R
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0B0A10)
val Panel = Color(0xFF17151F)
val Elevated = Color(0xFF24212F)
val Accent = Color(0xFFF04444)
val AccentSoft = Color(0xFFFFB4B4)
val Peach = Color(0xFFFFCBB7)
val SoftWhite = Color(0xFFF7F3EF)
val Muted = Color(0xFFAAA5B8)
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val MauriFont = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable fun MeloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, secondary = AccentSoft, background = Ink,
            onBackground = SoftWhite, surface = Panel, onSurface = SoftWhite, surfaceVariant = Elevated,
            onSurfaceVariant = Muted, outline = Color(0xFF3C374A), error = Color(0xFFFFB4AB), primaryContainer = Color(0xFF492126), onPrimaryContainer = Color(0xFFFFDADB),
            secondaryContainer = Color(0xFF492126), onSecondaryContainer = Color(0xFFFFDADB), surfaceTint = Accent),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = MauriFont, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp, letterSpacing = (-1.1).sp),
            headlineMedium = TextStyle(fontFamily = MauriFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 33.sp),
            titleLarge = TextStyle(fontFamily = MauriFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
            titleMedium = TextStyle(fontFamily = MauriFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
            bodyLarge = TextStyle(fontFamily = MauriFont, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = MauriFont, fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontFamily = MauriFont, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
            labelLarge = TextStyle(fontFamily = MauriFont, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontFamily = MauriFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            bodySmall = TextStyle(fontFamily = MauriFont, fontSize = 12.sp, lineHeight = 18.sp),
        ),
        content = content,
    )
}
