package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.service.CarlifeConnectService
import java.io.Closeable
import java.io.IOException
import java.util.UUID

internal class BluetoothReceiverProbe(
    private val connectivity: ConnectivityManager,
) : Closeable {
    private var wifiRequest: Closeable? = null
    private var sockets: CarPlaySockets? = null
    private var server: BluetoothServerSocket? = null
    private var peer: BluetoothSocket? = null
    @Volatile private var closed = false

    fun run(
        adapter: BluetoothAdapter,
        receiverName: String,
        pin: String,
        deviceId: String,
        onStreamStarted: () -> Unit,
        receiverAddress: String = "",
        onStage: (String) -> Unit,
    ) {
        try {
            var reconnectUntil = 0L
            while (!closed) {
                var streamed = false
                try {
                    val acceptTimeout = if (reconnectUntil == 0L) 30_000 else
                        (reconnectUntil - SystemClock.elapsedRealtime()).coerceIn(1L, 30_000L).toInt()
                    runAttempt(adapter, receiverName, pin, deviceId, acceptTimeout, receiverAddress, {
                        streamed = true
                        onStreamStarted()
                    }, onStage)
                } catch (error: IOException) {
                    if (closed) return
                    if (!captureReady() || (!streamed && SystemClock.elapsedRealtime() >= reconnectUntil)) throw error
                    onStage("投屏连接已断开 · 等待接收端重新连接")
                } finally {
                    closeAttempt()
                }
                if (!captureReady()) break
                if (streamed) reconnectUntil = SystemClock.elapsedRealtime() + 90_000
                if (SystemClock.elapsedRealtime() >= reconnectUntil) break
            }
        } finally {
            close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runAttempt(
        adapter: BluetoothAdapter,
        receiverName: String,
        pin: String,
        deviceId: String,
        acceptTimeout: Int,
        receiverAddress: String,
        onStreamStarted: () -> Unit,
        onStage: (String) -> Unit,
    ) {
        val attempt = CarPlaySockets()
        val listener = synchronized(this) {
            if (closed) throw IOException("蓝牙连接已停止")
            sockets = attempt
            adapter.listenUsingRfcommWithServiceRecord("CarPlay", SERVICE_UUID).also { server = it }
        }
        onStage("正在等待配对设备 $receiverName 连接")
        val connection = listener.accept(acceptTimeout)
        synchronized(this) {
            if (closed) {
                connection.close()
                throw IOException("蓝牙连接已停止")
            }
            peer = connection
            runCatching { listener.close() }
            server = null
        }
        val device = connection.remoteDevice
        val matches = if (receiverAddress.isEmpty()) device.name.equals(receiverName, ignoreCase = true)
        else device.address.equals(receiverAddress, ignoreCase = true)
        if (device.bondState != BluetoothDevice.BOND_BONDED || !matches) {
            throw IOException("忽略了来自其他蓝牙设备的连接")
        }
        onStage("蓝牙 RFCOMM 已连接")
        var accessPoint: ReceiverWifiRequest? = null
        fun requestWifi(wifi: ReceiverWifi) {
            if (pin.isEmpty() || Build.VERSION.SDK_INT < 29 || accessPoint != null) return
            onStage("正在连接车机 Wi-Fi · 如有系统提示，请确认")
            val request = ReceiverWifiRequest(connectivity, attempt::close)
            synchronized(this) {
                if (closed) throw IOException("CarPlay 会话已停止")
                request.start(wifi)
                wifiRequest = request
                accessPoint = request
            }
        }
        val endpoint = Iap2LinkProbe.run(connection.inputStream, connection.outputStream, ::requestWifi, onStage)
        if (pin.isNotEmpty()) {
            endpoint.wifi?.let(::requestWifi)
            if (Build.VERSION.SDK_INT >= 29) accessPoint?.let { request ->
                val network = request.awaitNetwork()
                attempt.useNetwork(network, connectivity.getLinkProperties(network)?.interfaceName)
                onStage("已连接车机 Wi-Fi")
            }
            onStage(CarPlayReceiverProbe.verify(
                endpoint.host, endpoint.port, pin, endpoint.certificate, deviceId, attempt, onStreamStarted, onStage,
            ))
        }
    }

    private fun captureReady() = CarlifeConnectService.state.value.displayId != null &&
        CarlifeConnectService.state.value.active

    @Synchronized
    private fun closeAttempt() {
        sockets?.close()
        runCatching { wifiRequest?.close() }
        wifiRequest = null
        runCatching { peer?.close() }
        runCatching { server?.close() }
        sockets = null
        peer = null
        server = null
    }

    @Synchronized
    override fun close() {
        closed = true
        closeAttempt()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
