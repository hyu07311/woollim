package com.vintagesheep.woollim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vintagesheep.woollim.MainActivity
import com.vintagesheep.woollim.R
import com.vintagesheep.woollim.core.WoollimEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 울림 앱이 뒤로 가도(내비 사용 중) 감지 장치 연결·진동·경고를 유지하는 포그라운드 서비스.
 * 상단 알림 "울림 감지 중"이 떠 있는 동안 동작한다.
 */
class DetectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayController

    override fun onCreate() {
        super.onCreate()
        WoollimEngine.init(this)
        WoollimEngine.start()
        startInForeground()
        overlay = OverlayController(this).also { it.bind(scope) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        overlay.remove()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "감지 상태", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "울림이 위험음을 감지하고 있는 동안 표시됩니다"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_woollim)
            .setContentTitle("울림 감지 중")
            .setContentText("다른 앱을 쓰는 동안에도 위험음을 알려 드려요")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    companion object {
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DetectionService::class.java))
        }
    }
}
