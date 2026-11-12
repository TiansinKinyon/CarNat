package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.IOException
import java.util.UUID

internal class BluetoothReceiverProbe : Closeable {
    private var server: BluetoothServerSocket? = null
    private var peer: BluetoothSocket? = null
    private var closed = false

    @SuppressLint("MissingPermission")
    fun run(adapter: BluetoothAdapter, receiverName: String, pin: String, onStage: (String) -> Unit) {
        val listener = synchronized(this) {
            if (closed) throw IOException("蓝牙诊断已停止")
            adapter.listenUsingRfcommWithServiceRecord("CarPlay", SERVICE_UUID).also { server = it }
        }
        try {
            onStage("正在等待配对设备 $receiverName 连接")
            val connection = listener.accept(30_000)
            synchronized(this) {
                if (closed) {
                    connection.close()
                    throw IOException("蓝牙诊断已停止")
                }
                peer = connection
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
                onStage(CarPlayReceiverProbe.verify(endpoint.host, endpoint.port, pin, endpoint.certificate, onStage))
            }
        } finally {
            close()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { peer?.close() }
        runCatching { server?.close() }
        peer = null
        server = null
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
