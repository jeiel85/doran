package com.jeiel85.doran.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "도란도란" 저녁 식탁 팔레트 (DECISIONS.md D-14): 오트 크림 바탕, 짙은 잉크, 소나무 초록, 살구 강조.
 */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val pine: Color,
    val pineDeep: Color,
    val pineSoft: Color,
    val apricot: Color,
    val apricotSoft: Color,
    val butter: Color,
    val expense: Color,
    val expenseSoft: Color,
    val income: Color,
    val incomeSoft: Color,
    val transfer: Color,
    val danger: Color,
    val shadow: Color,
    val dark: Boolean,
)

private val Light = Palette(
    bg = Color(0xFFF6F2EA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFFBF8F2), line = Color(0xFFECE5D9),
    ink = Color(0xFF1E1C27), inkSoft = Color(0xFF6F6A79), inkFaint = Color(0xFFA6A1AE),
    pine = Color(0xFF2E7A62), pineDeep = Color(0xFF1B4D3E), pineSoft = Color(0xFFE2F0E9),
    apricot = Color(0xFFFF9F5A), apricotSoft = Color(0xFFFFEEDF), butter = Color(0xFFFFD36B),
    expense = Color(0xFFE85A4C), expenseSoft = Color(0xFFFDE9E6), income = Color(0xFF3577EE), incomeSoft = Color(0xFFE6EEFD),
    transfer = Color(0xFF8C8798), danger = Color(0xFFD9473A), shadow = Color(0x14402A10), dark = false,
)

private val Dark = Palette(
    bg = Color(0xFF121414), surface = Color(0xFF1B1E1D), surface2 = Color(0xFF222625), line = Color(0xFF2D3231),
    ink = Color(0xFFF3F0EA), inkSoft = Color(0xFFAEAAB3), inkFaint = Color(0xFF6F6C75),
    pine = Color(0xFF5CC39F), pineDeep = Color(0xFF8FDCC0), pineSoft = Color(0xFF1E3A31),
    apricot = Color(0xFFFFAD72), apricotSoft = Color(0xFF3A2A1E), butter = Color(0xFFFFD36B),
    expense = Color(0xFFFF7A6D), expenseSoft = Color(0xFF3A2321), income = Color(0xFF79A6FF), incomeSoft = Color(0xFF1F2A40),
    transfer = Color(0xFF9B97A6), danger = Color(0xFFFF6B5E), shadow = Color(0x00000000), dark = true,
)

/** 구성원 색: 0 블루, 1 로즈, 2 틸, 3 라벤더, 4 앰버, 5 슬레이트. */
val memberColors = listOf(Color(0xFF4D7CFE), Color(0xFFFF6F91), Color(0xFF22B3A0), Color(0xFFA47CFF), Color(0xFFF5A524), Color(0xFF7C8B9C))
fun memberColor(i: Int) = memberColors[((i % memberColors.size) + memberColors.size) % memberColors.size]

/** 차트 조각 색(카테고리 순위대로). */
val chartColors = listOf(
    Color(0xFF2E7A62), Color(0xFFFF9F5A), Color(0xFF3577EE), Color(0xFFFF6F91), Color(0xFFA47CFF),
    Color(0xFF22B3A0), Color(0xFFF5A524), Color(0xFF7C8B9C), Color(0xFFD7A8F0), Color(0xFF9CCB6A),
)

val LocalPalette = staticCompositionLocalOf { Light }

object P {
    val c: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
}

/** 금액은 고정폭 숫자로 줄을 맞춥니다. */
val Money = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

@Composable
fun DoranTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = p.pine, onPrimary = Color(0xFF0E1F19), background = p.bg, surface = p.surface, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.surface2, outline = p.line, secondary = p.apricot, error = p.danger,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
    ) else lightColorScheme(
        primary = p.pine, onPrimary = Color.White, background = p.bg, surface = p.surface, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.surface2, outline = p.line, secondary = p.apricot, error = p.danger,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
    )
    val base = Typography()
    val type = Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, fontSize = 34.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, fontSize = 24.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
