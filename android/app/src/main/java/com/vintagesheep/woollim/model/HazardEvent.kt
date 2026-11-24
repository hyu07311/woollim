package com.vintagesheep.woollim.model

import org.json.JSONObject

/**
 * Pi ↔ 앱 메시지 포맷 (전송 계층과 무관: BLE Notify / WebSocket / Mock 공통)
 *
 * Pi → 앱 (감지 이벤트)
 *   {"v":1,"id":42,"cls":"siren","lvl":"urgent","conf":0.94,
 *    "trend":"approach","dur_ms":2300,"t_pi":1732412345678}
 *   cls   : siren | horn
 *   lvl   : urgent | caution | log
 *   trend : approach | recede | steady | unknown
 *
 * 앱 → Pi (주행 맥락)
 *   {"v":1,"speed_kmh":0,"t_app":1732412345700}
 */
const val PROTOCOL_VERSION = 1

enum class SoundClass(val wire: String, val label: String) {
    SIREN("siren", "사이렌"),
    HORN("horn", "경적");

    companion object {
        fun fromWire(s: String): SoundClass? = entries.firstOrNull { it.wire == s }
    }
}

enum class RiskLevel(val wire: String, val label: String) {
    URGENT("urgent", "긴급"),
    CAUTION("caution", "주의"),
    LOG_ONLY("log", "기록");

    companion object {
        fun fromWire(s: String): RiskLevel? = entries.firstOrNull { it.wire == s }
    }
}

enum class Trend(val wire: String, val label: String) {
    APPROACH("approach", "가까워지는 중"),
    RECEDE("recede", "멀어지는 중"),
    STEADY("steady", "거리 유지"),
    UNKNOWN("unknown", "");

    companion object {
        fun fromWire(s: String?): Trend = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

data class HazardEvent(
    val id: Int,
    val soundClass: SoundClass,
    val level: RiskLevel,
    val confidence: Float,
    val trend: Trend,
    val durationMs: Int,
    /** Pi 기준 판정 시각(epoch ms). 앱 시계와 동기화 전에는 비교에 쓰지 말 것. */
    val tPiMs: Long,
    /** 앱 수신 시각(SystemClock.uptimeMillis) — 수신→표시 지연 측정용 */
    val receivedUptimeMs: Long,
    /** 앱 수신 시각(epoch ms) — 화면 표시·리포트용 */
    val receivedWallMs: Long,
) {
    companion object {
        /** 잘못된 메시지는 null (앱이 죽지 않도록 예외를 삼킨다) */
        fun parse(json: String, receivedUptimeMs: Long, receivedWallMs: Long): HazardEvent? =
            runCatching {
                val o = JSONObject(json)
                if (o.optInt("v", -1) != PROTOCOL_VERSION) return null
                HazardEvent(
                    id = o.getInt("id"),
                    soundClass = SoundClass.fromWire(o.getString("cls")) ?: return null,
                    level = RiskLevel.fromWire(o.getString("lvl")) ?: return null,
                    confidence = o.getDouble("conf").toFloat(),
                    trend = Trend.fromWire(o.optString("trend", "unknown")),
                    durationMs = o.optInt("dur_ms", 0),
                    tPiMs = o.optLong("t_pi", 0L),
                    receivedUptimeMs = receivedUptimeMs,
                    receivedWallMs = receivedWallMs,
                )
            }.getOrNull()

        fun speedMessage(speedKmh: Int, tAppMs: Long): String =
            JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("speed_kmh", speedKmh)
                .put("t_app", tAppMs)
                .toString()
    }
}
