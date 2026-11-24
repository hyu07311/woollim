package com.vintagesheep.woollim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.source.ConnectionState
import com.vintagesheep.woollim.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: MainViewModel, onSeeAll: () -> Unit) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val tripStart by vm.tripStartMs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("오늘도 안전 운전하세요") { ConnectionChip(connection) }
        Hero(connection, levels)
        StatsRow(tripStart, history)
        RecentEvents(history, onSeeAll)
        Button(
            onClick = vm::endTrip,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary, contentColor = Color.White),
        ) {
            Text("운행 종료", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConnectionChip(state: ConnectionState) {
    val dot = when (state) {
        ConnectionState.CONNECTED -> Palette.Ok
        ConnectionState.CONNECTING -> Palette.Caution
        ConnectionState.DISCONNECTED -> Palette.Urgent
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.PrimaryTint)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot)
        Spacer(Modifier.width(6.dp))
        Text(state.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    }
}

/** 참고 디자인의 중앙 원형 링 + 소리가 퍼지는 리플 */
@Composable
private fun Hero(state: ConnectionState, levels: List<Float>) {
    val active = state == ConnectionState.CONNECTED
    val accent = if (active) Palette.Primary else Palette.Disabled

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Ripples(Palette.PrimarySoft, Modifier.fillMaxSize(), rings = 3, periodMs = 3000, maxAlpha = 0.45f)
            }
            // 얇은 바깥 링
            Box(
                Modifier
                    .size(200.dp)
                    .border(1.5.dp, if (active) Palette.PrimarySoft else Palette.Line, CircleShape)
            )
            // 중앙 원
            Box(
                Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Brush.linearGradient(listOf(Palette.PrimarySoft, Palette.Primary))
                        else Brush.linearGradient(listOf(Palette.LogTint, Palette.Disabled))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (active) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (active) Palette.Primary else Palette.Urgent)
            Spacer(Modifier.width(6.dp))
            Text(
                if (active) "주변 위험음 감지 중" else "감지 장치와 연결되지 않음",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Palette.Primary else Palette.Urgent,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (active) "사이렌·경적이 들리면 진동과 화면으로 알려드려요"
            else "지금은 알림을 받을 수 없어요. 장치 전원을 확인하세요",
            fontSize = 14.sp,
            color = Palette.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        LevelMeter(levels, accent, Modifier.fillMaxWidth(0.7f).height(36.dp))
        Spacer(Modifier.height(4.dp))
        Text("마이크 입력", fontSize = 11.sp, color = Palette.TextMuted)
    }
}

@Composable
private fun LevelMeter(levels: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val minH = 3.dp.toPx()
        levels.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(minH)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * v.coerceIn(0f, 1f)),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

@Composable
private fun StatsRow(tripStartMs: Long, history: List<HazardEvent>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sec = ((now - tripStartMs) / 1000).coerceAtLeast(0)
    val elapsed = "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    val urgent = history.count { it.level == RiskLevel.URGENT }
    val caution = history.count { it.level == RiskLevel.CAUTION }
    val logged = history.count { it.level == RiskLevel.LOG_ONLY }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("주행 시간", elapsed, null, Modifier.weight(1f))
        StatCard("이번 운행 알림", "${urgent + caution}건", "긴급 $urgent · 주의 $caution · 기록 $logged", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String?, modifier: Modifier) {
    Column(modifier.tintCard().padding(16.dp)) {
        Text(label, fontSize = 13.sp, color = Palette.PrimaryDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 11.sp, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun RecentEvents(history: List<HazardEvent>, onSeeAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근 감지", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSeeAll).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("전체보기", fontSize = 13.sp, color = Palette.Primary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.Primary, modifier = Modifier.size(18.dp))
            }
        }
        if (history.isEmpty()) {
            EmptyCard("아직 감지된 위험음이 없어요")
        }
        history.take(3).forEach { EventRow(it) }
    }
}
