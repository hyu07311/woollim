package com.vintagesheep.woollim.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vintagesheep.woollim.alert.AlertSpec
import com.vintagesheep.woollim.core.WoollimEngine
import com.vintagesheep.woollim.ui.CautionBanner
import com.vintagesheep.woollim.ui.UrgentAlert
import com.vintagesheep.woollim.ui.theme.WoollimTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 울림 화면이 보이지 않을 때(내비 등 다른 앱 사용 중) 경고를 다른 앱 위에 띄운다.
 *  - 긴급: 전체 화면 (탭하거나 자동 닫힘 시간이 지나면 사라짐)
 *  - 주의: 상단 배너만. 배너 밖 터치는 아래 앱(내비)으로 그대로 전달
 * "다른 앱 위에 표시" 권한이 없으면 아무것도 하지 않는다.
 */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val current = mutableStateOf<AlertSpec?>(null)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var viewIsFull = false

    fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(WoollimEngine.alert, WoollimEngine.uiVisible) { alert, uiVisible ->
                if (uiVisible) null else alert
            }.distinctUntilChanged().collect(::render)
        }
    }

    private fun render(spec: AlertSpec?) {
        if (spec == null || !Settings.canDrawOverlays(context)) {
            remove()
            return
        }
        current.value = spec
        if (view != null && viewIsFull == spec.fullScreen) return // 같은 형태면 내용만 교체
        remove()
        current.value = spec
        add(spec.fullScreen)
    }

    private fun add(full: Boolean) {
        val o = OverlayOwner().also { it.resume() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                WoollimTheme {
                    val s = current.value ?: return@WoollimTheme
                    if (s.fullScreen) {
                        UrgentAlert(s, latencyMs = null, showDebug = false,
                            onDismiss = WoollimEngine::dismissAlert, onShown = WoollimEngine::onAlertShown)
                    } else {
                        CautionBanner(s, latencyMs = null, showDebug = false,
                            onDismiss = WoollimEngine::dismissAlert, onShown = WoollimEngine::onAlertShown)
                    }
                }
            }
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = if (full) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP }
        }
        runCatching { wm.addView(v, params) }.onFailure {
            o.destroy()
            return
        }
        view = v
        owner = o
        viewIsFull = full
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }
}

/** 서비스 안의 ComposeView가 동작하려면 Lifecycle·SavedState 소유자가 필요하다 */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun resume() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
