package com.vintagesheep.woollim.alert

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 위험 알림 진동은 '알람' 용도로 요청한다.
 * 일반 용도로 요청하면 무음 모드·방해 금지 모드에서 진동이 꺼질 수 있는데,
 * 청각장애 운전자에게는 진동이 핵심 전달 수단이므로 알람과 같은 우선순위로 둔다.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** 진동 모터가 없는 기기(Wi-Fi 태블릿 등)에서는 false */
    val available: Boolean get() = vibrator.hasVibrator()

    fun play(spec: VibrationSpec) {
        if (!available) return
        vibrator.cancel()
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(spec.timings, spec.amplitudes, spec.repeatIndex)
        } else {
            // 세기 조절 미지원 기기: off/on 교대 패턴으로 대체
            VibrationEffect.createWaveform(spec.timings, spec.repeatIndex)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    fun stop() = vibrator.cancel()
}
