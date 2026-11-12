package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.baidu.carlife.service.CarlifeConnectService
import java.io.Closeable
import java.io.IOException
import java.util.UUID

internal class BluetoothReceiverProbe : Closeable {
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
        onStage: (String) -> Unit,
    ) {
        try {
            do {
                var streamed = false
                try {
                    runAttempt(adapter, receiverName, pin, deviceId, {
                        streamed = true
                        onStreamStarted()
                    }, onStage)
                } catch (error: IOException) {
                    if (closed) return
                    if (!streamed || !captureReady()) throw error
                    onStage("投屏连接已断开 · 等待接收端重新连接")
                } finally {
                    closeAttempt()
                }
            } while (!closed && streamed && captureReady())
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
        val connection = listener.accept(30_000)
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
        if (device.bondState != BluetoothDevice.BOND_BONDED ||
            !device.name.equals(receiverName, ignoreCase = true)
        ) {
            throw IOException("忽略了与指定名称不符的蓝牙连接")
        }
        onStage("蓝牙 RFCOMM 已连接")
        val endpoint = Iap2LinkProbe.run(connection.inputStream, connection.outputStream, onStage)
        if (pin.isNotEmpty()) {
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
