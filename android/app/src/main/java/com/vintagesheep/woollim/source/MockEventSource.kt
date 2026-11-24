package com.vintagesheep.woollim.source

import android.os.SystemClock
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.model.Trend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Pi 없이 UI·진동을 개발/시연하기 위한 가짜 이벤트 공급원 */
class MockEventSource : EventSource {
    override val name = "시연 모드 (Mock)"

    private val _events = MutableSharedFlow<HazardEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connection = _connection.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val inputLevel = _level.asStateFlow()

    private var job: Job? = null
    private var nextId = 1

    @Volatile
    private var loudUntilUptimeMs = 0L

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            _connection.value = ConnectionState.CONNECTING
            delay(800)
            _connection.value = ConnectionState.CONNECTED
            var base = 0.15f
            while (isActive) {
                base = (base + Random.nextFloat() * 0.06f - 0.03f).coerceIn(0.08f, 0.3f)
                val loud = SystemClock.uptimeMillis() < loudUntilUptimeMs
                _level.value = if (loud) 0.7f + Random.nextFloat() * 0.3f else base
                delay(50) // 20 Hz
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _connection.value = ConnectionState.DISCONNECTED
        _level.value = 0f
    }

    override suspend fun sendSpeed(speedKmh: Int) = Unit

    fun trigger(cls: SoundClass, level: RiskLevel, trend: Trend) {
        val nowUp = SystemClock.uptimeMillis()
        val nowWall = System.currentTimeMillis()
        loudUntilUptimeMs = nowUp + 2_500
        _events.tryEmit(
            HazardEvent(
                id = nextId++,
                soundClass = cls,
                level = level,
                confidence = 0.85f + Random.nextFloat() * 0.14f,
                trend = trend,
                durationMs = 2_000,
                tPiMs = nowWall,
                receivedUptimeMs = nowUp,
                receivedWallMs = nowWall,
            )
        )
    }
}
