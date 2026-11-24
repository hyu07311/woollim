package com.vintagesheep.woollim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vintagesheep.woollim.model.HazardEvent
import com.vintagesheep.woollim.model.RiskLevel
import com.vintagesheep.woollim.model.SoundClass
import com.vintagesheep.woollim.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 운행 기록 (P1에서 운행 리포트 + 오탐 피드백으로 확장) */
@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val urgent = history.count { it.level == RiskLevel.URGENT }
    val caution = history.count { it.level == RiskLevel.CAUTION }
    val logged = history.count { it.level == RiskLevel.LOG_ONLY }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("운행 기록") }
        item {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().tintCard().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SummaryCell("긴급", urgent, Palette.Urgent)
                SummaryCell("주의", caution, Palette.CautionDeep)
                SummaryCell("기록", logged, Palette.TextMuted)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (history.isEmpty()) {
            item { EmptyCard("이번 운행에서 감지된 위험음이 없어요") }
        }
        items(history, key = { "${it.id}-${it.receivedUptimeMs}" }) { EventRow(it) }
    }
}

@Composable
private fun SummaryCell(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 13.sp, color = Palette.TextMuted)
    }
}

@Composable
fun EventRow(e: HazardEvent) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }
    val (bg, fg) = levelColors(e.level)
    Row(
        Modifier.fillMaxWidth().card(16.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (e.soundClass == SoundClass.SIREN) Icons.Filled.Warning else Icons.Filled.Campaign,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.soundClass.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Spacer(Modifier.width(6.dp))
                LevelBadge(e.level)
            }
            val sub = listOfNotNull(
                e.trend.label.takeIf { it.isNotEmpty() },
                "신뢰도 ${(e.confidence * 100).toInt()}%",
            ).joinToString(" · ")
            Text(sub, fontSize = 12.sp, color = Palette.TextMuted)
        }
        Text(fmt.format(Date(e.receivedWallMs)), fontSize = 13.sp, color = Palette.TextMuted)
    }
}

@Composable
fun EmptyCard(message: String) {
    Column(
        Modifier.fillMaxWidth().card(16.dp).padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(36.dp, Palette.PrimarySoft)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 14.sp, color = Palette.TextMuted, textAlign = TextAlign.Center)
    }
}
