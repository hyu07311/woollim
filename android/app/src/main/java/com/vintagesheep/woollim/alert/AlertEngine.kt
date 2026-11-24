package com.vintagesheep.woollim.alert

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.model.Trend
import com.vintagesheep.woollim.ui.theme.Palette

/** 진동 파형: timings[i] 동안 amplitudes[i] 세기. repeatIndex=-1이면 1회 */
class VibrationSpec(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeatIndex: Int,
)

data class AlertSpec(
    val event: HazardEvent,
    val title: String,
    val guidance: String,
    val background: Color,
    val foreground: Color,
    val icon: ImageVector,
    val fullScreen: Boolean,
    val autoDismissMs: Long,
    val vibration: VibrationSpec,
)

/**
 * 위험 단계 → 화면·진동·문구.
 * 규칙 기반 템플릿만 사용 (실시간 경로에 생성형 AI 없음).
 * 단일 마이크라 방향을 알 수 없으므로 문구에 방향 표현을 넣지 않는다.
 */
object AlertEngine {
    // 긴급: 길고 강한 진동 반복 (800ms on / 200ms off)
    private val URGENT_VIBRATION = VibrationSpec(
        timings = longArrayOf(0, 800, 200),
        amplitudes = intArrayOf(0, 255, 0),
        repeatIndex = 1,
    )

    // 주의: 짧게 2회
    private val CAUTION_VIBRATION = VibrationSpec(
        timings = longArrayOf(0, 180, 120, 180),
        amplitudes = intArrayOf(0, 200, 0, 200),
        repeatIndex = -1,
    )

    // 초기값(실측 후 조정). 긴급은 Pi가 "소리 종료" 이벤트를 보내게 되면 그 시점 + 3s로 변경 예정
    const val URGENT_AUTO_DISMISS_MS = 8_000L
    const val CAUTION_AUTO_DISMISS_MS = 5_000L

    fun vibrationFor(level: RiskLevel): VibrationSpec? = when (level) {
        RiskLevel.URGENT -> URGENT_VIBRATION
        RiskLevel.CAUTION -> CAUTION_VIBRATION
        RiskLevel.LOG_ONLY -> null
    }

    fun specFor(e: HazardEvent): AlertSpec? {
        val icon = when (e.soundClass) {
            SoundClass.SIREN -> Icons.Filled.Warning
            SoundClass.HORN -> Icons.Filled.Campaign
        }
        return when (e.level) {
            RiskLevel.URGENT -> AlertSpec(
                event = e,
                title = when (e.soundClass) {
                    SoundClass.SIREN ->
                        if (e.trend == Trend.APPROACH) "긴급차량 접근 중" else "긴급차량 사이렌"
                    SoundClass.HORN -> "강한 경적"
                },
                guidance = when (e.soundClass) {
                    SoundClass.SIREN -> "주변 확인 후 양보 준비"
                    SoundClass.HORN -> "속도를 줄이고 주변 차량 확인"
                },
                background = Palette.Urgent,
                foreground = Color.White,
                icon = icon,
                fullScreen = true,
                autoDismissMs = URGENT_AUTO_DISMISS_MS,
                vibration = URGENT_VIBRATION,
            )

            RiskLevel.CAUTION -> AlertSpec(
                event = e,
                title = when (e.soundClass) {
                    SoundClass.SIREN -> "사이렌 감지"
                    SoundClass.HORN -> "경적 감지"
                },
                guidance = when (e.soundClass) {
                    SoundClass.SIREN -> "주변 확인, 양보 준비"
                    SoundClass.HORN -> "주변 차량 주의"
                },
                background = Palette.Caution,
                foreground = Palette.OnCaution,
                icon = icon,
                fullScreen = false,
                autoDismissMs = CAUTION_AUTO_DISMISS_MS,
                vibration = CAUTION_VIBRATION,
            )

            RiskLevel.LOG_ONLY -> null // 알림 없음, 기록만
        }
    }
}
