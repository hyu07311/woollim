package com.vintagesheep.woollim.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vintagesheep.woollim.core.WoollimEngine
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass

/**
 * 화면용 창구. 실제 상태와 로직은 WoollimEngine(앱 전체에 하나)에 있어서
 * 화면이 사라져도 감지·경고가 계속된다.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = WoollimEngine.also {
        it.init(app)
        it.start()
    }

    val source = engine.source
    val demoMode = engine.demoMode
    val hapticsAvailable: Boolean get() = engine.hapticsAvailable

    val connection = engine.connection
    val alert = engine.alert
    val history = engine.history
    val levels = engine.levels
    val tripStartMs = engine.tripStartMs
    val lastLatencyMs = engine.lastLatencyMs

    fun dismissAlert() = engine.dismissAlert()
    fun onAlertShown(e: HazardEvent, shownUptimeMs: Long) = engine.onAlertShown(e, shownUptimeMs)
    fun previewVibration(level: RiskLevel) = engine.previewVibration(level)
    fun demoTrigger(cls: SoundClass, level: RiskLevel) = engine.demoTrigger(cls, level)
    fun setDemoMode(on: Boolean) = engine.setDemoMode(on)
    fun reconnect() = engine.reconnect()

    // TODO(P1): 운행 리포트 화면으로 이동 + 피드백 수집
    fun endTrip() = engine.endTrip()
}
