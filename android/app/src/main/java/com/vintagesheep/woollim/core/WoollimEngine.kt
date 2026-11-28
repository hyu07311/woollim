package com.vintagesheep.woollim.core

import android.content.Context
import com.vintagesheep.woollim.alert.AlertEngine
import com.vintagesheep.woollim.alert.AlertSpec
import com.vintagesheep.woollim.alert.Haptics
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.model.Trend
import com.vintagesheep.woollim.source.BleEventSource
import com.vintagesheep.woollim.source.ConnectionState
import com.vintagesheep.woollim.source.EventSource
import com.vintagesheep.woollim.source.MockEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 감지·경고 상태의 단일 소유자 (앱 프로세스에 하나).
 * 화면(MainViewModel)과 백그라운드 서비스(DetectionService·오버레이)가 같은 상태를 본다.
 * 그래서 앱이 뒤로 가도 이벤트 수신·진동·경고가 계속된다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object WoollimEngine {
    const val LEVEL_BARS = 32
    private const val HISTORY_MAX = 100
    private const val PREFS = "woollim"
    private const val KEY_DEMO = "demo_mode"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var appContext: Context
    private lateinit var haptics: Haptics
    private lateinit var ble: BleEventSource
    private val mock = MockEventSource()

    private val _source = MutableStateFlow<EventSource>(mock)
    val source: StateFlow<EventSource> = _source.asStateFlow()

    /** true = 시연 모드(가짜 이벤트), false = 실제 감지 장치(BLE) */
    val demoMode: StateFlow<Boolean> =
        _source.map { it === mock }.stateIn(scope, SharingStarted.Eagerly, true)

    val connection: StateFlow<ConnectionState> =
        _source.flatMapLatest { it.connection }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

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

    /** 울림 화면이 사용자에게 보이는 중인지 (아니면 오버레이로 경고) */
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    private var started = false
    private var dismissJob: Job? = null

    val hapticsAvailable: Boolean get() = haptics.available

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        haptics = Haptics(appContext)
        ble = BleEventSource(appContext)
        val demo = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEMO, false)
        _source.value = if (demo) mock else ble
    }

    /** 여러 번 불러도 한 번만 시작한다 (Activity·Service 양쪽에서 호출) */
    fun start() {
        check(::appContext.isInitialized) { "init() 먼저 호출" }
        if (started) return
        started = true
        _source.value.start(scope)
        scope.launch { _source.flatMapLatest { it.events }.collect(::onEvent) }
        scope.launch {
            _source.flatMapLatest { it.inputLevel }.collect { v -> _levels.update { it.drop(1) + v } }
        }
    }

    fun setDemoMode(on: Boolean) {
        val next = if (on) mock else ble
        val current = _source.value
        if (current === next) return
        current.stop()
        _source.value = next
        if (started) next.start(scope)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEMO, on).apply()
    }

    /** 감지 장치 다시 찾기 (BLE일 때만) */
    fun reconnect() {
        val s = _source.value
        if (s === ble) {
            s.stop()
            if (started) s.start(scope)
        }
    }

    fun setUiVisible(visible: Boolean) {
        _uiVisible.value = visible
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
        dismissJob = scope.launch {
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
        scope.launch {
            delay(1_500)
            if (_alert.value == null) haptics.stop()
        }
    }

    fun demoTrigger(cls: SoundClass, level: RiskLevel) {
        val trend = if (cls == SoundClass.SIREN) Trend.APPROACH else Trend.STEADY
        (_source.value as? MockEventSource)?.trigger(cls, level, trend)
    }

    fun endTrip() {
        dismissAlert()
        _history.value = emptyList()
        _tripStartMs.value = System.currentTimeMillis()
    }
}
