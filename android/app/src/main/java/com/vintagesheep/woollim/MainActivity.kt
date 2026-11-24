package com.vintagesheep.woollim

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vintagesheep.woollim.ui.AlertLayer
import com.vintagesheep.woollim.ui.HistoryScreen
import com.vintagesheep.woollim.ui.HomeScreen
import com.vintagesheep.woollim.ui.MainViewModel
import com.vintagesheep.woollim.ui.SettingsScreen
import com.vintagesheep.woollim.ui.theme.Palette
import com.vintagesheep.woollim.ui.theme.WoollimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // 주행 중 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            WoollimTheme { WoollimApp() }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    HISTORY("기록", Icons.Outlined.History, Icons.Filled.History),
    HOME("홈", Icons.Outlined.Home, Icons.Filled.Home),
    SETTINGS("설정", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun WoollimApp(vm: MainViewModel = viewModel()) {
    val alert by vm.alert.collectAsStateWithLifecycle()
    val latency by vm.lastLatencyMs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    BackHandler(enabled = alert != null) { vm.dismissAlert() }
    BackHandler(enabled = alert == null && tab != Tab.HOME) { tab = Tab.HOME }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(vm, onSeeAll = { tab = Tab.HISTORY })
                    Tab.HISTORY -> HistoryScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
            BottomBar(tab) { tab = it }
        }
        AlertLayer(
            alert = alert,
            latencyMs = latency,
            showDebug = vm.isMock,
            onDismiss = vm::dismissAlert,
            onShown = vm::onAlertShown,
        )
    }
}

/** 참고 디자인의 3탭 하단 바 (가운데 홈) */
@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = Palette.Line)
            .background(Palette.Surface)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val selected = t == current
            val color = if (selected) Palette.Primary else Palette.Disabled
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(t) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (selected) t.iconSelected else t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(26.dp))
                Text(t.label, fontSize = 11.sp, color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
