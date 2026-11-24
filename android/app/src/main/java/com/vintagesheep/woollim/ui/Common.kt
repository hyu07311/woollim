package com.vintagesheep.woollim.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.ui.theme.Palette

/** 흰 카드 + 옅은 테두리 */
fun Modifier.card(radius: Dp = 20.dp): Modifier =
    this.clip(RoundedCornerShape(radius))
        .background(Palette.Surface)
        .border(1.dp, Palette.Line, RoundedCornerShape(radius))

/** 연핑크 틴트 카드 */
fun Modifier.tintCard(radius: Dp = 20.dp, color: Color = Palette.PrimaryTint): Modifier =
    this.clip(RoundedCornerShape(radius)).background(color)

/** 울림 브랜드 마크: 점 + 퍼져나가는 소리 호 2개 */
@Composable
fun BrandMark(size: Dp, color: Color = Palette.Primary) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s * 0.36f, s * 0.5f)
        drawCircle(color, radius = s * 0.18f, center = c)
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
        listOf(0.36f, 0.56f).forEachIndexed { i, r ->
            val rad = s * r
            drawArc(
                color = color.copy(alpha = if (i == 0) 0.85f else 0.5f),
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(c.x - rad, c.y - rad),
                size = Size(rad * 2, rad * 2),
                style = stroke,
            )
        }
    }
}

/** 화면 상단: 핑크 브랜드 라벨 + 큰 제목 (참고 디자인 헤더 패턴) */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(18.dp)
                Spacer(Modifier.width(4.dp))
                Text("울림", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Primary)
            }
            Spacer(Modifier.height(2.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        trailing()
    }
}

/** 퍼져나가는 원 (소리의 '울림'을 시각화). 3Hz 미만의 부드러운 움직임만 사용 */
@Composable
fun Ripples(
    color: Color,
    modifier: Modifier,
    rings: Int = 2,
    periodMs: Int = 2400,
    maxAlpha: Float = 0.35f,
    startFraction: Float = 0.45f,
) {
    val t = rememberInfiniteTransition(label = "ripple")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "p",
    )
    Canvas(modifier) {
        val base = size.minDimension / 2f
        repeat(rings) { i ->
            val f = (p + i.toFloat() / rings) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - f) * maxAlpha),
                radius = base * (startFraction + (1f - startFraction) * f),
            )
        }
    }
}

fun levelColors(level: RiskLevel): Pair<Color, Color> = when (level) {
    RiskLevel.URGENT -> Palette.Urgent to Color.White
    RiskLevel.CAUTION -> Palette.Caution to Palette.OnCaution
    RiskLevel.LOG_ONLY -> Palette.LogTint to Palette.TextMuted
}

@Composable
fun LevelBadge(level: RiskLevel) {
    val (bg, fg) = levelColors(level)
    Text(
        level.label,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
