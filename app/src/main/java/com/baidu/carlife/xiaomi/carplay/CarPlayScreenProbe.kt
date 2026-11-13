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
    private const val FIRST_FRAME_TIMEOUT_MS = 8_000L

    fun run(
        host: String,
        control: RtspControl,
        info: ByteArray,
        deviceId: String,
        keys: CarPlaySessionKeys,
        certificate: java.security.cert.X509Certificate,
        sockets: CarPlaySockets,
        onStreamStarted: () -> Unit,
        onStage: (String) -> Unit,
    ): String = CarPlayTiming(host).use { timing ->
        require(bluetoothAddress.matches(deviceId))
        val receiverInfo = parse(info)
        val displays = receiverInfo.objectForKey("displays") as? NSArray
        val display = displays?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
            it.objectForKey("uuid") is NSString
        } ?: throw ProtocolException("接收端 /info 缺少显示屏 UUID")
        val displayId = (display.objectForKey("uuid") as NSString).content
        val touchWidth = (display.objectForKey("widthPixels") as? NSNumber)?.intValue() ?: 0
        val touchHeight = (display.objectForKey("heightPixels") as? NSNumber)?.intValue() ?: 0

        onStage("正在建立 CarPlay 会话")
        val session = NSDictionary().apply {
            put("timingPort", timing.port)
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
        connect(sockets, host, eventPort).use { events ->
            CarPlayTouchEvents(events, keys, control, touchWidth, touchHeight, certificate, sockets::close, onStage).start()
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
            connect(sockets, host, dataPort).use { video ->
                openAudio(receiverInfo, host, control, keys, navigation = false).use { audio ->
                    openAudio(receiverInfo, host, control, keys, navigation = true).use { navigation ->
                        request(control, "RECORD", "/", NSDictionary())
                        audio?.start()
                        navigation?.start()
                        val connected = "主屏视频 TCP 已连接，RECORD 已确认 · TCP $dataPort"
                        if (!CarlifeConnectService.state.value.active) return connected
                        onStage(connected)
                        val heartbeat = Thread({
                        try {
                            while (!video.isClosed) {
                                Thread.sleep(3_000)
                                val reply = control.request("OPTIONS", "*")
                                if (reply.status in 500..599) {
                                    throw ProtocolException("CarPlay heartbeat returned ${reply.status}")
                                }
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (failure: java.io.IOException) {
                            if (!video.isClosed) {
                                android.util.Log.i("CarNatIap2", "CarPlay 控制通道失联：${failure.message}")
                                sockets.close()
                            }
                        }
                    }, "CarPlay connection heartbeat").apply { isDaemon = true; start() }
                    try {
                        return sendVideo(video, keys.video(connectionId), onStreamStarted, onStage)
                    } finally {
                        heartbeat.interrupt()
                    }
                    }
                }
            }
        }
    }

    private fun openAudio(
        info: NSDictionary, host: String, control: RtspControl, keys: CarPlaySessionKeys,
        navigation: Boolean,
    ): CarPlayMediaAudio? {
        val formats = info.objectForKey("audioFormats") as? NSArray ?: return null
        val audioType = if (navigation) "alert" else "media"
        val media = formats.array.filterIsInstance<NSDictionary>().filter {
            (it.objectForKey("audioType") as? NSString)?.content == audioType
        }
        fun mask(type: Int): Int = media.firstOrNull {
            (it.objectForKey("type") as? NSNumber)?.intValue() == type
        }?.let { (it.objectForKey("audioOutputFormats") as? NSNumber)?.intValue() } ?: 0
        val aacMask = mask(CarPlayMediaAudio.AAC_TYPE)
        val pcmMask = mask(CarPlayMediaAudio.PCM_TYPE)
        val (format, rate) = if (navigation) when {
            pcmMask and 0x4000 != 0 -> 0x4000 to 48_000
            pcmMask and 0x400 != 0 -> 0x400 to 44_100
            pcmMask and 0x10 != 0 -> 0x10 to 16_000
            else -> return null
        } else when {
            aacMask and 0x800000 != 0 -> 0x800000 to 48_000
            aacMask and 0x400000 != 0 -> 0x400000 to 44_100
            pcmMask and 0x800 != 0 -> 0x800 to 44_100
            pcmMask and 0x8000 != 0 -> 0x8000 to 48_000
            else -> return null
        }
        val aac = format >= 0x400000
        val type = if (aac) CarPlayMediaAudio.AAC_TYPE else CarPlayMediaAudio.PCM_TYPE
        val id = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)
        val stream = NSDictionary().apply {
            put("type", type)
            put("audioType", audioType)
            put("audioFormat", format)
            put("audioLatencyMs", if (navigation) 100 else if (aac) 350 else 120)
            put("streamConnectionID", id)
        }
        val body = NSDictionary().apply {
            put("streams", NSArray(1).apply { setValue(0, stream) })
        }
        val reply = try {
            parse(request(control, "SETUP", "/", body))
        } catch (failure: ProtocolException) {
            if (!navigation) throw failure
            android.util.Log.w("CarNatAudio", "Receiver did not negotiate navigation audio", failure)
            return null
        }
        val streams = reply.objectForKey("streams") as? NSArray
        val response = streams?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
            (it.objectForKey("type") as? NSNumber)?.intValue() == type &&
                (it.objectForKey("streamConnectionID") as? NSNumber)?.longValue()?.let { it == id } != false
        } ?: return null
        return CarPlayMediaAudio(host, port(response, "dataPort"), keys.video(id), rate, aac,
            stream = if (navigation) 2 else 1, outputChannels = if (navigation) 1 else 2,
        )
    }

    private fun sendVideo(
        video: Socket,
        key: ByteArray,
        onStreamStarted: () -> Unit,
        onStage: (String) -> Unit,
    ): String {
        val feed = CarWithVideoFeed.subscribe()
        try {
            CarlifeConnectService.requestKeyFrame()
            val writer = CarPlayVideoWriter(video.getOutputStream(), key)
            val firstFrameDeadline = SystemClock.elapsedRealtime() + FIRST_FRAME_TIMEOUT_MS
            var frames = 0
            var sentBytes = 0L
            var longestWriteMs = 0L
            var nextReport = SystemClock.elapsedRealtime() + 5_000
            var firstPtsUs = 0L
            var firstNtp = 0L
            while (!video.isClosed && CarlifeConnectService.state.value.active) {
                val now = SystemClock.elapsedRealtime()
                if (frames == 0 && now >= firstFrameDeadline) {
                    throw ProtocolException("CarWith 未提供可发送的视频关键帧")
                }
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
                    firstNtp = CarPlayTiming.now()
                }
                val elapsedUs = frame.ptsUs - firstPtsUs
                val offset = ((elapsedUs / 1_000_000) shl 32) +
                    (((elapsedUs % 1_000_000) shl 32) / 1_000_000)
                writer.writeAccessUnit(frame.payload, firstNtp + offset)
                frames++
                sentBytes += frame.payload.size
                longestWriteMs = maxOf(longestWriteMs, SystemClock.elapsedRealtime() - writeStarted)
                if (frames == 1) {
                    onStreamStarted()
                    onStage("正在投屏 · 已发送 CarWith 首个视频关键帧")
                }
            }
            if (frames == 0) throw ProtocolException("CarWith 未提供可发送的视频关键帧")
            return "投屏已结束 · 已发送 $frames 帧 CarWith 视频"
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

    private fun connect(sockets: CarPlaySockets, host: String, port: Int): Socket {
        val socket = sockets.open()
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
