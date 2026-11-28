package com.vintagesheep.woollim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vintagesheep.woollim.core.WoollimEngine
import com.vintagesheep.woollim.service.DetectionService
import com.vintagesheep.woollim.source.BleEventSource
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
        WoollimEngine.init(this)
        WoollimEngine.start()
        // 내비 등 다른 앱을 쓰는 동안에도 감지·경고 유지
        DetectionService.start(this)
        setContent {
            WoollimTheme { WoollimApp() }
        }
    }

    override fun onStart() {
        super.onStart()
        WoollimEngine.setUiVisible(true) // 화면이 보이면 앱 안에서 경고
    }

    override fun onStop() {
        WoollimEngine.setUiVisible(false) // 안 보이면 오버레이로 경고
        super.onStop()
    }
}

/** 처음 실행할 때 요청할 권한: 블루투스(감지 장치 연결) + 알림(감지 중 표시) */
private fun runtimePermissions(): Array<String> =
    (BleEventSource.requiredPermissions() +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    ).toTypedArray()

/** "다른 앱 위에 표시" 권한이 없을 때 홈 상단 안내 카드 */
@Composable
private fun OverlayPermissionCard(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.PrimaryTint)
            .clickable(onClick = onOpenSettings)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("내비 위에도 경고를 띄우려면", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("'다른 앱 위에 표시'를 허용해 주세요", fontSize = 13.sp, color = Palette.TextMuted)
        }
        Text("설정 열기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.PrimaryDeep)
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
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 감지 장치 연결 루프가 권한을 주기적으로 다시 확인한다 */ }
    LaunchedEffect(Unit) {
        val missing = runtimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    // 설정 화면에서 돌아올 때마다 오버레이 권한 다시 확인
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canOverlay = Settings.canDrawOverlays(context) }

    BackHandler(enabled = alert != null) { vm.dismissAlert() }
    BackHandler(enabled = alert == null && tab != Tab.HOME) { tab = Tab.HOME }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            if (!canOverlay && tab == Tab.HOME) {
                OverlayPermissionCard {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            }
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
            showDebug = demoMode,
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
