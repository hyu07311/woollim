package com.vintagesheep.woollim.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.source.ConnectionState
import com.vintagesheep.woollim.ui.theme.Palette

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val latency by vm.lastLatencyMs.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("설정")

        Section("연결 장치") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (connection) {
                        ConnectionState.CONNECTED -> Palette.Ok
                        ConnectionState.CONNECTING -> Palette.Caution
                        ConnectionState.DISCONNECTED -> Palette.Urgent
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(source.name, fontSize = 15.sp, color = Palette.Text, modifier = Modifier.weight(1f))
                Text(connection.label, fontSize = 13.sp, color = Palette.TextMuted)
            }
            if (!demoMode && connection != ConnectionState.CONNECTED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "라즈베리파이를 켜고 블루투스를 켜 두면 자동으로 연결돼요",
                    fontSize = 13.sp,
                    color = Palette.TextMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시연 모드", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Text("감지 장치 없이 가짜 이벤트로 확인", fontSize = 12.sp, color = Palette.TextMuted)
                }
                Switch(
                    checked = demoMode,
                    onCheckedChange = vm::setDemoMode,
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Primary),
                )
            }
            if (!demoMode) {
                Spacer(Modifier.height(8.dp))
                OutlinePill("감지 장치 다시 찾기", Palette.Primary, Modifier.fillMaxWidth(), vm::reconnect)
            }
        }

        Section("진동 패턴 미리 느껴보기") {
            if (vm.hapticsAvailable) {
                Text("화면을 보지 않아도 진동만으로 단계를 구분할 수 있어요", fontSize = 13.sp, color = Palette.TextMuted)
            } else {
                Text(
                    "이 기기에는 진동 모터가 없어요. 진동 알림은 스마트폰에서 확인해 주세요",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Urgent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePill("긴급 · 길게 반복", Palette.Urgent, Modifier.weight(1f)) { vm.previewVibration(RiskLevel.URGENT) }
                OutlinePill("주의 · 짧게 2번", Palette.CautionDeep, Modifier.weight(1f)) { vm.previewVibration(RiskLevel.CAUTION) }
            }
        }

        if (demoMode) {
            Section("시연 모드", trailing = "수신→표시 ${latency?.let { "${it}ms" } ?: "-"}") {
                Text("Pi 없이 가짜 이벤트로 알림 화면과 진동을 확인합니다", fontSize = 13.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledPill("긴급\n사이렌", Palette.Urgent, Color.White, Modifier.weight(1f)) {
                        vm.demoTrigger(SoundClass.SIREN, RiskLevel.URGENT)
                    }
                    FilledPill("주의\n경적", Palette.Caution, Palette.OnCaution, Modifier.weight(1f)) {
                        vm.demoTrigger(SoundClass.HORN, RiskLevel.CAUTION)
                    }
                    FilledPill("기록만\n경적", Palette.LogTint, Palette.Text, Modifier.weight(1f)) {
                        vm.demoTrigger(SoundClass.HORN, RiskLevel.LOG_ONLY)
                    }
                }
            }
        }

        // AI 윤리·안전: 보조 장치 고지
        Row(Modifier.fillMaxWidth().tintCard().padding(16.dp)) {
            Icon(Icons.Outlined.Info, null, tint = Palette.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "울림은 운전을 돕는 보조 장치예요. 모든 위험음을 감지하지 못할 수 있으니 " +
                    "항상 주변을 직접 확인해 주세요. 소리는 장치 안에서만 처리되며 저장·전송하지 않아요.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Palette.Text,
            )
        }
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            if (trailing != null) Text(trailing, fontSize = 12.sp, color = Palette.Primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun FilledPill(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(4.dp),
    ) {
        Text(label, textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
    }
}

@Composable
private fun OutlinePill(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
