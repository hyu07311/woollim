package com.vintagesheep.woollim.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vintagesheep.woollim.alert.AlertSpec
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.ui.theme.Palette
import kotlin.math.roundToInt

/**
 * 긴급 = 전체 화면 (참고 디자인의 SOS 화면처럼 중앙 큰 원), 주의 = 상단 플로팅 카드.
 * 깜빡임 없이 천천히 퍼지는 리플만 사용 (광과민성 발작 기준 3Hz 미만).
 */
@Composable
fun AlertLayer(
    alert: AlertSpec?,
    latencyMs: Long?,
    showDebug: Boolean,
    onDismiss: () -> Unit,
    onShown: (HazardEvent, Long) -> Unit,
) {
    // 퇴장 애니메이션 동안 내용을 유지하기 위해 마지막 값을 보관
    val lastFull = remember { arrayOfNulls<AlertSpec>(1) }
    val lastBanner = remember { arrayOfNulls<AlertSpec>(1) }
    if (alert != null) {
        if (alert.fullScreen) lastFull[0] = alert else lastBanner[0] = alert
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = alert != null && !alert.fullScreen,
            enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(100)),
            exit = slideOutVertically(tween(250)) { -it } + fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            lastBanner[0]?.let { CautionBanner(it, latencyMs, showDebug, onDismiss, onShown) }
        }
        AnimatedVisibility(
            visible = alert != null && alert.fullScreen,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(250)),
        ) {
            lastFull[0]?.let { UrgentAlert(it, latencyMs, showDebug, onDismiss, onShown) }
        }
    }
}

@Composable
private fun ReportShown(spec: AlertSpec, onShown: (HazardEvent, Long) -> Unit) {
    LaunchedEffect(spec.event.id) {
        withFrameNanos { } // 첫 프레임이 그려질 때까지 대기
        onShown(spec.event, SystemClock.uptimeMillis())
    }
}

@Composable
private fun UrgentAlert(
    spec: AlertSpec,
    latencyMs: Long?,
    showDebug: Boolean,
    onDismiss: () -> Unit,
    onShown: (HazardEvent, Long) -> Unit,
) {
    ReportShown(spec, onShown)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.Urgent, Palette.UrgentDeep)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // 상단 라벨
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(18.dp, Color.White)
            Spacer(Modifier.width(4.dp))
            Text("긴급 알림", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (showDebug && latencyMs != null) {
                Text("수신→표시 ${latencyMs}ms", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
            }
        }

        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 중앙 큰 원 + 퍼지는 리플
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Ripples(Color.White, Modifier.fillMaxSize(), rings = 3, periodMs = 2000, maxAlpha = 0.35f, startFraction = 0.6f)
                Box(
                    Modifier
                        .size(168.dp)
                        .shadow(20.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(spec.icon, contentDescription = null, tint = Palette.Urgent, modifier = Modifier.size(84.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${spec.event.soundClass.label} 감지",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                spec.title,
                fontSize = 38.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (spec.event.trend.label.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    spec.event.trend.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            // 행동 안내: 흰 알약 카드 (반전 대비로 가장 눈에 띄게)
            Text(
                spec.guidance,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Palette.UrgentDeep,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "신뢰도 ${(spec.event.confidence * 100).roundToInt()}%",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
            )
        }

        Text(
            "화면을 탭하면 닫힙니다",
            modifier = Modifier.align(Alignment.BottomCenter),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun CautionBanner(
    spec: AlertSpec,
    latencyMs: Long?,
    showDebug: Boolean,
    onDismiss: () -> Unit,
    onShown: (HazardEvent, Long) -> Unit,
) {
    ReportShown(spec, onShown)
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .shadow(18.dp, shape)
            .clip(shape)
            .background(spec.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(spec.icon, contentDescription = null, tint = Palette.CautionDeep, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = spec.foreground)
            Text(spec.guidance, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = spec.foreground.copy(alpha = 0.85f))
            if (showDebug && latencyMs != null) {
                Text("수신→표시 ${latencyMs}ms", fontSize = 11.sp, color = spec.foreground.copy(alpha = 0.6f))
            }
        }
    }
}
