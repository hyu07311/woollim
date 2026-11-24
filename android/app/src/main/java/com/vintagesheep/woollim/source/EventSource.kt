package com.vintagesheep.woollim.source

import com.vintagesheep.woollim.model.HazardEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState(val label: String) {
    DISCONNECTED("연결 끊김"),
    CONNECTING("연결 중"),
    CONNECTED("연결됨"),
}

/**
 * 감지 이벤트 공급원. UI·알림 로직은 이 인터페이스만 본다.
 * 구현체: MockEventSource(지금) → BleEventSource(Pi GATT) → WebSocketEventSource(BLE 실패 시 대안)
 */
interface EventSource {
    /** 화면에 표시할 공급원 이름 */
    val name: String

    val events: SharedFlow<HazardEvent>
    val connection: StateFlow<ConnectionState>

    /** Pi 마이크 입력 레벨 0..1 (정규화 RMS). "살아있음" 표시용 */
    val inputLevel: StateFlow<Float>

    fun start(scope: CoroutineScope)
    fun stop()

    /** 앱 → Pi: GPS 속도 (Step 3) */
    suspend fun sendSpeed(speedKmh: Int)
}
