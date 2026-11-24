package com.vintagesheep.woollim.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vintagesheep.woollim.alert.AlertEngine
import com.vintagesheep.woollim.alert.AlertSpec
import com.vintagesheep.woollim.alert.Haptics
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.model.Trend
import com.vintagesheep.woollim.source.EventSource
import com.vintagesheep.woollim.source.MockEventSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // TODO(Step 2): BleEventSource로 교체
    val source: EventSource = MockEventSource()
    val isMock: Boolean get() = source is MockEventSource

    private val haptics = Haptics(app)
    val hapticsAvailable: Boolean get() = haptics.available

    val connection = source.connection

    private val _alert = MutableStateFlow<AlertSpec?>(null)
    val alert: StateFlow<AlertSpec?> = _alert.asStateFlow()

    private val _history = MutableStateFlow<List<HazardEvent>>(emptyList())
    val history: StateFlow<List<HazardEvent>> = _history.asStateFlow()

    private val _levels = MutableStateFlow(List(LEVEL_BARS) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private val _tripStartMs = MutableStateFlow(System.currentTimeMillis())
    val tripStartMs: StateFlow<Long> = _tripStartMs.asStateFlow()

    /** 앱 수신 → 화면 첫 프레임 표시까지 (ms) */
    private val _lastLatencyMs = MutableStateFlow<Long?>(null)
    val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()

    private var dismissJob: Job? = null

    init {
        source.start(viewModelScope)
        viewModelScope.launch { source.events.collect(::onEvent) }
        viewModelScope.launch {
            source.inputLevel.collect { v -> _levels.update { it.drop(1) + v } }
        }
    }

    private fun onEvent(e: HazardEvent) {
        _history.update { (listOf(e) + it).take(HISTORY_MAX) }

        val spec = AlertEngine.specFor(e) ?: return // 기록만

        // 긴급 경고가 떠 있는 동안 주의 경고가 덮어쓰지 않도록
        val current = _alert.value
        if (current != null && current.event.level == RiskLevel.URGENT && e.level != RiskLevel.URGENT) return

        _alert.value = spec
        haptics.play(spec.vibration)

        dismissJob?.cancel()
        dismissJob = viewModelScope.launch {
            delay(spec.autoDismissMs)
            clearAlert()
        }
    }

    fun dismissAlert() {
        dismissJob?.cancel()
        clearAlert()
    }

    private fun clearAlert() {
        _alert.value = null
        haptics.stop()
    }

    fun onAlertShown(e: HazardEvent, shownUptimeMs: Long) {
        _lastLatencyMs.value = shownUptimeMs - e.receivedUptimeMs
    }

    /** 설정 화면: 진동 패턴 미리보기 (반복 패턴은 1.5초 후 정지) */
    fun previewVibration(level: RiskLevel) {
        val spec = AlertEngine.vibrationFor(level) ?: return
        haptics.play(spec)
        viewModelScope.launch {
            delay(1_500)
            if (_alert.value == null) haptics.stop()
        }
    }

    fun demoTrigger(cls: SoundClass, level: RiskLevel) {
        val trend = if (cls == SoundClass.SIREN) Trend.APPROACH else Trend.STEADY
        (source as? MockEventSource)?.trigger(cls, level, trend)
    }

    fun endTrip() {
        // TODO(P1): 운행 리포트 화면으로 이동 + 피드백 수집
        dismissAlert()
        _history.value = emptyList()
        _tripStartMs.value = System.currentTimeMillis()
    }

    override fun onCleared() {
        haptics.stop()
        source.stop()
    }

    companion object {
        const val LEVEL_BARS = 32
        const val HISTORY_MAX = 100
    }
}
