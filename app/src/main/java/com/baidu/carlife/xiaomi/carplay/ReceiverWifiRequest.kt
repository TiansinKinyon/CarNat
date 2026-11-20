package com.baidu.carlife.xiaomi.carplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun missingReceiverPermissions(context: Context): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
}.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

@RequiresApi(29)
internal class ReceiverWifiRequest(
    private val connectivity: ConnectivityManager,
    private val onLost: () -> Unit,
) : Closeable {
    private val ready = CountDownLatch(1)
    @Volatile private var network: Network? = null
    @Volatile private var closed = false
    private var registered = false
    @Volatile private var requestStarted = 0L

    private fun elapsedMs() = SystemClock.elapsedRealtime() - requestStarted

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            this@ReceiverWifiRequest.network = network
            ProjectionLog.event("Wi-Fi · Android onAvailable · 用时=${elapsedMs()}ms")
            ready.countDown()
        }

        override fun onUnavailable() {
            ProjectionLog.event("Wi-Fi · Android onUnavailable · 用时=${elapsedMs()}ms · 系统未提供拒绝或关联失败的具体原因")
            ready.countDown()
        }

        override fun onLost(network: Network) {
            this@ReceiverWifiRequest.network = null
            ProjectionLog.event("Wi-Fi · Android onLost · 用时=${elapsedMs()}ms")
            ready.countDown()
            onLost.invoke()
        }
    }

    @Synchronized
    fun start(wifi: ReceiverWifi) {
        if (closed) throw IOException("车机 Wi-Fi 连接已停止")
        val specifier = try {
            WifiNetworkSpecifier.Builder().setSsid(wifi.ssid).apply {
                when (wifi.security) {
                    0 -> Unit
                    2, 3 -> setWpa2Passphrase(wifi.passphrase)
                    4 -> setWpa3Passphrase(wifi.passphrase)
                    else -> throw IOException("车机热点使用了 Android 无法自动连接的加密方式")
                }
            }.build()
        } catch (_: IllegalArgumentException) {
            throw IOException("车机提供的 Wi-Fi 配置无效")
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        try {
            requestStarted = SystemClock.elapsedRealtime()
            ProjectionLog.event("Wi-Fi · 请求车机热点 · 加密类型=${wifi.security} · 信道=${wifi.channel ?: "未知"} · 超时=45000ms")
            connectivity.requestNetwork(request, callback, 45_000)
            registered = true
        } catch (_: SecurityException) {
            throw IOException("缺少连接车机 Wi-Fi 所需的权限")
        }
    }

    fun awaitNetwork(timeoutMs: Long = 46_000): Network {
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            ProjectionLog.event("Wi-Fi · ${timeoutMs}ms 内未收到 Android 网络回调")
        }
        if (closed) throw IOException("车机 Wi-Fi 连接已停止")
        return network ?: throw IOException("未能连接车机 Wi-Fi，请确认 Wi-Fi 已打开并接受系统连接请求")
    }

    @Synchronized
    override fun close() {
        closed = true
        ready.countDown()
        if (registered) {
            registered = false
            connectivity.unregisterNetworkCallback(callback)
        }
    }
}
