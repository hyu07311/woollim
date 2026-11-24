package com.vintagesheep.woollim.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 참고 디자인(앱디자인참고.jpg) 기반: 흰 배경 + 코랄 핑크 포인트 + 연핑크 틴트 카드.
 * 경고색(긴급 빨강, 주의 주황)은 브랜드색과 분리해 의미를 유지한다.
 */
object Palette {
    // 브랜드
    val Primary = Color(0xFFEC6A84)
    val PrimaryDeep = Color(0xFFD94F6B)
    val PrimarySoft = Color(0xFFF7B3C0)
    val PrimaryTint = Color(0xFFFDEFF2)

    // 바탕·텍스트
    val Bg = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val Line = Color(0xFFF1E7EA)
    val Text = Color(0xFF222222)
    val TextMuted = Color(0xFF8E8E93)
    val Disabled = Color(0xFFC7C7CC)

    // 상태
    val Ok = Color(0xFF34C38F)
    val Urgent = Color(0xFFE5304A)
    val UrgentDeep = Color(0xFFC21D3A)
    val Caution = Color(0xFFFFA928)
    val CautionDeep = Color(0xFFE08600)
    val OnCaution = Color(0xFF2B2B2B)
    val LogTint = Color(0xFFF2F2F5)
}

private val WoollimColors = lightColorScheme(
    primary = Palette.Primary,
    onPrimary = Color.White,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.PrimaryTint,
    onSurfaceVariant = Palette.TextMuted,
    outline = Palette.Line,
)

@Composable
fun WoollimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WoollimColors, content = content)
}
