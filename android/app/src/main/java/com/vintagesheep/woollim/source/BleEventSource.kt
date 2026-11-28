package com.vintagesheep.woollim.source

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vintagesheep.woollim.model.HazardEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Raspberry Pi 감지 장치(GATT 서버, pi/woollim_ble.py)에서 이벤트를 받는다.
 *
 * 스캔 → 연결 → MTU 247 요청 → 서비스 탐색 → event·level Notify 구독.
 * 연결이 끊기면 처음(스캔)부터 다시 시도한다.
 */
@SuppressLint("MissingPermission") // ready()에서 권한을 확인한 뒤에만 호출
class BleEventSource(private val context: Context) : EventSource {
    override val name = "감지 장치 (Bluetooth)"

    private val _events = MutableSharedFlow<HazardEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connection = _connection.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val inputLevel = _level.asStateFlow()

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var loopJob: Job? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var lastNotifyUptimeMs = 0L
    private val eventBuf = java.io.ByteArrayOutputStream()
    private val linkDown = Channel<Unit>(Channel.CONFLATED)
    private val pendingNotify = ArrayDeque<BluetoothGattCharacteristic>()

    override fun start(scope: CoroutineScope) {
        if (loopJob != null) return
        loopJob = scope.launch { connectLoop() }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        closeGatt()
        _connection.value = ConnectionState.DISCONNECTED
        _level.value = 0f
    }

    /** 속도 전송은 이번 범위에서 구현하지 않음 */
    override suspend fun sendSpeed(speedKmh: Int) = Unit

    // ─────────────────────────── 연결 루프 ───────────────────────────

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            if (!ready()) {
                _connection.value = ConnectionState.DISCONNECTED
                delay(2_000)
                continue
            }
            _connection.value = ConnectionState.CONNECTING
            // 블루투스 링크가 아직 살아 있으면 Pi가 광고를 멈춰 스캔에 안 잡힐 수 있다 → 이미 연결된 장치부터 확인
            val device = alreadyConnectedPi() ?: scanOnce(SCAN_TIMEOUT_MS)
            if (device == null) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(10_000)
                continue
            }
            linkDown.tryReceive() // 이전 신호 비우기
            lastNotifyUptimeMs = SystemClock.uptimeMillis()
            synchronized(eventBuf) { eventBuf.reset() }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            waitUntilLinkDown()
            backoffMs = 1_000L
            _connection.value = ConnectionState.DISCONNECTED
            _level.value = 0f
            delay(1_000)
        }
    }

    /**
     * 연결이 끊길 때까지 대기.
     * Pi 프로그램만 다시 시작하면 블루투스 링크는 살아 있는데 구독이 사라져서
     * "연결됨"인데 아무것도 안 오는 상태가 된다. level은 10 Hz로 계속 오므로
     * 일정 시간 Notify가 없으면 끊고 처음부터 다시 연결한다.
     */
    private suspend fun waitUntilLinkDown() {
        while (true) {
            if (withTimeoutOrNull(1_000) { linkDown.receive() } != null) return
            val silentMs = SystemClock.uptimeMillis() - lastNotifyUptimeMs
            val limit = if (_connection.value == ConnectionState.CONNECTED) SILENCE_LIMIT_MS else CONNECT_LIMIT_MS
            if (silentMs > limit) {
                closeGatt() // close() 후에는 콜백이 오지 않으므로 직접 빠져나간다
                return
            }
        }
    }

    /** 블루투스가 켜져 있고 필요한 권한이 모두 있는지 */
    private fun ready(): Boolean {
        val a = adapter ?: return false
        if (!requiredPermissions().all { granted(it) }) return false
        return a.isEnabled
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private suspend fun scanOnce(timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (matches(result) && cont.isActive) {
                            runCatching { scanner.stopScan(this) }
                            cont.resume(result.device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private fun alreadyConnectedPi(): BluetoothDevice? =
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.getConnectedDevices(BluetoothProfile.GATT)
                ?.firstOrNull { it.name?.contains("woollim", ignoreCase = true) == true }
        }.getOrNull()

    /** 서비스 UUID가 광고에 있거나, 장치 이름에 "woollim"이 들어 있으면 울림 감지 장치로 본다 */
    private fun matches(r: ScanResult): Boolean {
        val rec = r.scanRecord
        if (rec?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true) return true
        val name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull()
        return name?.contains("woollim", ignoreCase = true) == true
    }

    private fun closeGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    // ─────────────────────────── GATT 콜백 ───────────────────────────

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // JSON이 기본 20 byte보다 크므로 MTU를 올린 뒤 서비스 탐색
                if (!g.requestMtu(MTU)) g.discoverServices()
            } else {
                runCatching { g.close() }
                if (gatt === g) gatt = null
                linkDown.trySend(Unit)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                g.disconnect()
                return
            }
            pendingNotify.clear()
            listOfNotNull(svc.getCharacteristic(EVENT_UUID), svc.getCharacteristic(LEVEL_UUID))
                .forEach(pendingNotify::addLast)
            enableNextNotify(g)
        }

        // Pi 쪽 서비스 구성이 바뀌었다고 알려 오면(프로그램 재시작 등) 다시 탐색·구독 (Android 12 이상)
        override fun onServiceChanged(g: BluetoothGatt) {
            g.discoverServices()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            enableNextNotify(g) // 디스크립터 쓰기는 한 번에 하나씩만 가능
        }

        // Android 13 이상
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(c.uuid, value)
        }

        // Android 12 이하
        @Deprecated("Android 12 이하용")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.let { handle(c.uuid, it) }
            }
        }
    }

    private fun enableNextNotify(g: BluetoothGatt) {
        val c = pendingNotify.removeFirstOrNull()
        if (c == null) {
            _connection.value = ConnectionState.CONNECTED
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID) ?: return enableNextNotify(g)
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun handle(uuid: UUID, value: ByteArray) {
        lastNotifyUptimeMs = SystemClock.uptimeMillis()
        when (uuid) {
            EVENT_UUID -> onEventChunk(value)
            LEVEL_UUID -> if (value.isNotEmpty()) _level.value = (value[0].toInt() and 0xFF) / 255f
        }
    }

    /** Pi는 이벤트 JSON을 20 byte 조각으로 나눠 보내고 끝에 '\n'을 붙인다 → 줄 단위로 이어 붙여 해석 */
    private fun onEventChunk(chunk: ByteArray) {
        val lines = mutableListOf<String>()
        synchronized(eventBuf) {
            for (b in chunk) {
                if (b == NEWLINE) {
                    lines += String(eventBuf.toByteArray(), Charsets.UTF_8)
                    eventBuf.reset()
                } else {
                    eventBuf.write(b.toInt())
                }
            }
            if (eventBuf.size() > MAX_LINE_BYTES) eventBuf.reset() // 깨진 데이터 방어
        }
        for (line in lines) {
            HazardEvent.parse(
                line,
                receivedUptimeMs = SystemClock.uptimeMillis(),
                receivedWallMs = System.currentTimeMillis(),
            )?.let { _events.tryEmit(it) }
        }
    }

    companion object {
        // pi/woollim_ble.py 와 같아야 한다
        val SERVICE_UUID: UUID = UUID.fromString("8f1e0001-6b2c-4d3a-9e5f-77a1c0d2e301")
        val EVENT_UUID: UUID = UUID.fromString("8f1e0002-6b2c-4d3a-9e5f-77a1c0d2e301")
        val LEVEL_UUID: UUID = UUID.fromString("8f1e0003-6b2c-4d3a-9e5f-77a1c0d2e301")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MTU = 247
        private const val SCAN_TIMEOUT_MS = 10_000L
        /** 연결된 뒤 Notify가 이 시간 이상 없으면 재연결 (level이 10 Hz로 오므로 충분히 여유) */
        private const val SILENCE_LIMIT_MS = 5_000L
        /** 연결 시도 후 구독 완료까지 기다리는 최대 시간 */
        private const val CONNECT_LIMIT_MS = 20_000L
        private const val NEWLINE = '\n'.code.toByte()
        private const val MAX_LINE_BYTES = 1_024

        /** 스캔·연결에 필요한 런타임 권한 */
        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
