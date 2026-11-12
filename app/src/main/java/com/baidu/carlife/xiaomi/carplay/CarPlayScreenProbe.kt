package com.baidu.carlife.xiaomi.carplay

import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.service.CarlifeConnectService
import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** Negotiates the primary display on an authenticated control connection. */
internal object CarPlayScreenProbe {
    val bluetoothAddress = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val plistHeaders = mapOf("Content-Type" to "application/x-apple-binary-plist")
    private const val MAIN_SCREEN = 110
    const val VIDEO_DIAGNOSTIC_MS = 30_000L

    fun run(
        host: String,
        control: RtspControl,
        info: ByteArray,
        deviceId: String,
        keys: CarPlaySessionKeys,
        certificate: java.security.cert.X509Certificate,
        onStage: (String) -> Unit,
    ): String {
        require(bluetoothAddress.matches(deviceId))
        val displays = parse(info).objectForKey("displays") as? NSArray
        val display = displays?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
            it.objectForKey("uuid") is NSString
        } ?: throw ProtocolException("接收端 /info 缺少显示屏 UUID")
        val displayId = (display.objectForKey("uuid") as NSString).content
        val touchWidth = (display.objectForKey("widthPixels") as? NSNumber)?.intValue() ?: 0
        val touchHeight = (display.objectForKey("heightPixels") as? NSNumber)?.intValue() ?: 0

        onStage("正在建立 CarPlay 会话")
        val session = NSDictionary().apply {
            put("deviceID", deviceId.uppercase(Locale.ROOT))
            put("name", "CarNat")
            put("model", Build.MODEL)
            put("osName", "Android")
            put("osVersion", Build.VERSION.RELEASE)
            put("sourceVersion", "540.31")
            put("sessionUUID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
            put("sessionCorrelationUUID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
        }
        val eventPort = port(parse(request(control, "SETUP", "/setup", session)), "eventPort")
        connect(host, eventPort).use { events ->
            CarPlayTouchEvents(events, keys, control, touchWidth, touchHeight, certificate, onStage).start()
            onStage("事件通道已连接，正在协商主屏视频")
            val connectionId = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)
            val stream = NSDictionary().apply {
                put("type", MAIN_SCREEN)
                put("streamConnectionID", connectionId)
                put("uuid", displayId)
                put("latencyMs", 25)
            }
            val request = NSDictionary().apply {
                put("streams", NSArray(1).apply { setValue(0, stream) })
            }
            val reply = parse(request(control, "SETUP", "/", request))
            val streams = reply.objectForKey("streams") as? NSArray
            val screen = streams?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
                (it.objectForKey("type") as? NSNumber)?.longValue() == MAIN_SCREEN.toLong()
            } ?: throw ProtocolException("接收端未返回主屏流")
            val dataPort = port(screen, "dataPort")
            connect(host, dataPort).use { video ->
                request(control, "RECORD", "/", NSDictionary())
                val connected = "主屏视频 TCP 已连接，RECORD 已确认 · TCP $dataPort"
                if (!CarlifeConnectService.state.value.active) return connected
                onStage(connected)
                return sendVideo(video, keys.video(connectionId), onStage)
            }
        }
    }

    private fun sendVideo(video: Socket, key: ByteArray, onStage: (String) -> Unit): String {
        val feed = CarWithVideoFeed.subscribe()
        try {
            val writer = CarPlayVideoWriter(video.getOutputStream(), key)
            val deadline = SystemClock.elapsedRealtime() + VIDEO_DIAGNOSTIC_MS
            var frames = 0
            var sentBytes = 0L
            var longestWriteMs = 0L
            var nextReport = SystemClock.elapsedRealtime() + 5_000
            var firstPtsUs = 0L
            var firstUnixUs = 0L
            while (SystemClock.elapsedRealtime() < deadline && CarlifeConnectService.state.value.active) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextReport) {
                    val (encoded, dropped) = feed.counts()
                    android.util.Log.i("CarNatVideo", "encoded=$encoded sent=$frames dropped=$dropped payloadBytes=$sentBytes maxWriteMs=$longestWriteMs")
                    nextReport = now + 5_000
                }
                val frame = feed.poll(200) ?: continue
                val writeStarted = SystemClock.elapsedRealtime()
                frame.configuration?.let { writer.writeConfiguration(it, frame.width, frame.height) }
                if (frames == 0) {
                    firstPtsUs = frame.ptsUs
                    firstUnixUs = System.currentTimeMillis() * 1000
                }
                val unixUs = firstUnixUs + frame.ptsUs - firstPtsUs
                val ntpSeconds = unixUs / 1_000_000 + 2_208_988_800L
                val ntpFraction = (unixUs % 1_000_000 shl 32) / 1_000_000
                writer.writeAccessUnit(frame.payload, (ntpSeconds shl 32) or ntpFraction)
                frames++
                sentBytes += frame.payload.size
                longestWriteMs = maxOf(longestWriteMs, SystemClock.elapsedRealtime() - writeStarted)
                if (frames == 1) onStage("已发送 CarWith 首个视频关键帧")
            }
            if (frames == 0) throw ProtocolException("CarWith 未提供可发送的视频关键帧")
            return "主屏诊断已发送 $frames 帧 CarWith 视频"
        } finally {
            CarWithVideoFeed.unsubscribe(feed)
        }
    }

    private fun request(control: RtspControl, method: String, path: String, body: NSDictionary): ByteArray {
        val response = control.request(method, path, plistHeaders, BinaryPropertyListWriter.writeToArray(body))
        if (response.status !in 200..299) {
            throw ProtocolException("$method $path 失败（RTSP ${response.status}）")
        }
        return response.body
    }

    private fun parse(body: ByteArray): NSDictionary =
        BinaryPropertyListParser.parse(body) as? NSDictionary
            ?: throw ProtocolException("接收端返回的二进制 plist 不是字典")

    private fun port(body: NSDictionary, key: String): Int {
        val number = body.objectForKey(key) as? NSNumber
        val value = number?.takeIf { it.isInteger }?.longValue()
        return value?.takeIf { it in 1..65535 }?.toInt()
            ?: throw ProtocolException("接收端未返回有效的 $key")
    }

    private fun connect(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 5_000)
            return socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }
}
