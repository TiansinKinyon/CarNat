package com.baidu.carlife.xiaomi.carplay

import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Negotiates the primary display on an authenticated control connection. */
internal object CarPlayScreenProbe {
    val bluetoothAddress = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val plistHeaders = mapOf("Content-Type" to "application/x-apple-binary-plist")
    private const val MAIN_SCREEN = CarPlayDisplay.MAIN_SCREEN
    private const val FIRST_KEY_FRAME_TIMEOUT_MS = 8_000L
    private const val VIDEO_WRITE_TIMEOUT_MS = 5_000L
    private val FORMAT_REJECTIONS = setOf(400, 406, 415, 451, 456, 461)

    fun run(
        host: InetAddress,
        control: RtspControl,
        info: ByteArray?,
        deviceId: String,
        keys: CarPlaySessionKeys,
        certificate: java.security.cert.X509Certificate,
        sockets: CarPlaySockets,
        onStreamStarted: () -> Unit,
        onResolution: (CarPlayResolution, VideoCodec) -> Unit,
        preferences: ProjectionPreferences,
        onAudio: (Boolean, CarPlayAudioInfo) -> Unit,
        onStage: (String) -> Unit,
        protocol: ReceiverProtocol = ReceiverProtocol.GENERIC,
        readResolutionOnly: Boolean = false,
        receiverPort: Int = 7000,
        mguHealth: MguWifiHealth? = null,
        power: () -> Iap2PowerStatus = { Iap2PowerStatus() },
    ): String = CarPlayTiming(host, sockets.openDatagram()).use { timing ->
        require(bluetoothAddress.matches(deviceId))
        val sessionUuid = UUID.randomUUID()
        val address = host.hostAddress ?: throw ProtocolException("连接缺少车机地址")
        val authority = if (':' in address) "[${address.replace("%", "%25")}]" else address
        val sessionTarget = if (protocol.isVehicle) "rtsp://$authority/${sessionUuid.mostSignificantBits.toULong()}" else "/"
        val audioTarget = if (protocol.isVehicle) "rtsp://$authority:$receiverPort/" else "/"
        fun record() {
            if (protocol.isVehicle) {
                val reply = control.request("RECORD", sessionTarget, plistHeaders)
                VehicleScreenResources.requireAcknowledgement("RECORD", reply)
            } else {
                request(control, "RECORD", "/", NSDictionary())
            }
        }

        onStage("正在建立 CarPlay 会话")
        val session = NSDictionary().apply {
            put("timingPort", timing.port)
            put("keepAliveLowPower", true)
            put("deviceID", deviceId.uppercase(Locale.ROOT))
            put("name", "CarNat")
            put("model", Build.MODEL)
            put("osName", "Android")
            put("osVersion", Build.VERSION.RELEASE)
            put("sourceVersion", if (protocol == ReceiverProtocol.VOLKSWAGEN) CarPlayControlInvitation.SOURCE_VERSION else "540.31")
            put("sessionUUID", sessionUuid.toString().uppercase(Locale.ROOT))
            if (protocol.isVehicle) put("macAddress", deviceId.uppercase(Locale.ROOT))
            put("sessionCorrelationUUID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
        }
        fun readDisplay(receiverInfo: NSDictionary): CarPlayDisplay =
            CarPlayDisplay.fromReceiverInfo(receiverInfo, protocol).also { display ->
                val resolution = display.resolution
                ProjectionLog.event("车机主屏参数 · ${resolution.width}×${resolution.height}/${resolution.frameRate} fps")
            }

        val previousInfo = info?.let(::parse)
        val previousDisplay = if (protocol.isVehicle) null else readDisplay(checkNotNull(previousInfo))
        val setupTarget = if (protocol.isVehicle) sessionTarget else "/setup"
        val offerHevc = !readResolutionOnly && preferences.videoPreference != VideoPreference.H264 &&
            VideoCodec.hardwareHevcEncoder() != null
        val (sessionReply, codec) = negotiateVideoCodec(control, setupTarget, session, offerHevc)
        val currentInfo = if (protocol.isVehicle) CarPlayReceiverProbe.readInfo(control, protocol, required = false) else null
        val receiverInfo = currentInfo?.let(::parse) ?: previousInfo ?: throw ProtocolException("车机未提供会话显示信息")
        val display = previousDisplay ?: readDisplay(receiverInfo)
        val resolution = display.resolution
        onResolution(resolution, codec)
        if (readResolutionOnly) {
            if (protocol.isVehicle) {
                try {
                    control.request("TEARDOWN", sessionTarget, plistHeaders)
                } catch (failure: IOException) {
                    ProjectionLog.failure("分辨率探测结束时车机已断开", failure)
                }
            }
            return "已读取车机主屏分辨率"
        }
        val eventPort = port(sessionReply, "eventPort")
        val keepAlivePort = (sessionReply.objectForKey("keepAlivePort") as? NSNumber)
            ?.takeIf { it.isInteger }?.longValue()?.takeIf { it in 1L..65_535L }?.toInt()
        connect(sockets, host, eventPort).use { events ->
            val resources = if (protocol.isVehicle) VehicleScreenResources(receiverInfo, protocol) else null
            val mgu = protocol.isBmw && (receiverInfo.objectForKey("model") as? NSString)
                ?.content?.let { it == "MGU" || it.startsWith("MGU-") } == true
            val handoff = if (mgu) MguBluetoothHandoff(control, deviceId) else null
            CarPlayTouchEvents(
                events, keys, control, resolution.width, resolution.height, certificate,
                sockets::close, onStage, resources?.let { state -> state::onCommand }, protocol, power,
                onNetworkIapAuthenticated = { if (mgu) mguHealth?.ready() },
            ).start()
            val heartbeat = if (protocol.isVehicle) startHeartbeat(control, sockets, events, host, keepAlivePort, if (mgu) mguHealth else null) else null
            try {
                fun startAudio(beforeStart: () -> Unit = {}): java.io.Closeable {
                    val streams = mutableListOf<java.io.Closeable>()
                    fun closeAudio() = streams.asReversed().forEach { runCatching { it.close() } }
                    try {
                        val audio = openAudio(receiverInfo, host, control, keys, sockets, preferences, onAudio,
                            audioTarget, navigation = false)?.also(streams::add)
                        val navigation = openAudio(receiverInfo, host, control, keys, sockets, preferences, onAudio,
                            audioTarget, navigation = true)?.also(streams::add)
                        val microphone = if (preferences.useCarMicrophone) {
                            CarPlayMicrophone.open(receiverInfo, control, keys, sockets, host, audioTarget)?.also(streams::add)
                        } else null
                        beforeStart()
                        microphone?.start()
                        audio?.start()
                        navigation?.start()
                        return java.io.Closeable(::closeAudio)
                    } catch (failure: Exception) {
                        closeAudio()
                        throw failure
                    }
                }

                if (resources != null) {
                    record()
                    resources.publish(control)
                    resources.claimInitial()
                    var audio: java.io.Closeable? = null
                    try {
                        return runVehicleScreen(control, sockets, events, host, display.uuid, sessionTarget, protocol,
                            keys, codec, resources, onStreamStarted, onStage,
                            ensureAudio = {
                                handoff?.request()
                                if (audio == null) audio = startAudio()
                            })
                    } finally {
                        audio?.close()
                    }
                }

                onStage("事件通道已连接，正在协商主屏视频")
                val connectionId = nextVideoConnectionId()
                val dataPort = setupScreen(control, sessionTarget, display.uuid, connectionId, 25)
                connect(sockets, host, dataPort).use { video ->
                    return startAudio(::record).use {
                        val connected = "主屏视频 TCP 已连接，RECORD 已确认 · TCP $dataPort"
                        if (!CarlifeConnectService.state.value.active) connected else {
                            onStage(connected)
                            val videoHeartbeat = startHeartbeat(control, sockets, video, host, keepAlivePort)
                            try {
                                sendVideo(video, keys.video(connectionId), codec, onStreamStarted, onStage)
                            } finally {
                                videoHeartbeat.interrupt()
                            }
                        }
                    }
                }
            } finally {
                heartbeat?.interrupt()
            }
        }
    }

    internal fun runVehicleScreen(
        control: RtspControl, sockets: CarPlaySockets, events: Socket, host: InetAddress,
        uuid: String, target: String, protocol: ReceiverProtocol, keys: CarPlaySessionKeys,
        codec: VideoCodec, resources: VehicleScreenResources, onStreamStarted: () -> Unit, onStage: (String) -> Unit,
        ensureAudio: () -> Unit = {},
        keepRunning: () -> Boolean = { CarlifeConnectService.state.value.active },
        pump: (Socket, ByteArray, VideoCodec, () -> Unit, (String) -> Unit, () -> Unit) -> String = ::sendVideo,
    ): String {
        var announced = -1L
        var failedRevision: Long? = null
        var started = false
        var waiting = false
        while (!events.isClosed && keepRunning()) {
            val snapshot = resources.snapshot()
            if (snapshot.revision != announced) {
                resources.publish(control)
                announced = snapshot.revision
            }
            if (!snapshot.owned || failedRevision == snapshot.revision) {
                ensureAudio()
                if (!waiting) onStage("车机会话仍在运行 · 等待车机恢复主屏")
                waiting = true
                resources.waitForChange(snapshot)
                continue
            }
            if (!resources.allows(snapshot)) continue
            waiting = false

            val id = nextVideoConnectionId()
            var negotiated = false
            var startingAudio = false
            var video: Socket? = null
            try {
                val stream = screenStream(uuid, id, if (protocol.isBmw) 75 else 25)
                val response = control.request("SETUP", target, plistHeaders, BinaryPropertyListWriter.writeToArray(stream))
                if (response.status in FORMAT_REJECTIONS) {
                    ProjectionLog.event("车机暂未接受主屏 SETUP · RTSP ${response.status}")
                    failedRevision = snapshot.revision
                    continue
                }
                negotiated = response.status in 200..299
                val dataPort = screenPort(response)
                if (!resources.allows(snapshot)) continue
                val connected = connect(sockets, host, dataPort)
                video = connected
                if (!resources.install(snapshot, connected)) continue
                startingAudio = true
                ensureAudio()
                startingAudio = false
                if (!resources.allows(snapshot)) continue
                onStage("主屏视频 TCP 已连接 · TCP $dataPort")
                pump(connected, keys.video(id), codec, {
                    if (!started) {
                        started = true
                        onStreamStarted()
                    }
                }, onStage, {
                    val current = resources.snapshot()
                    if (current.grant == snapshot.grant && current.revision != announced) {
                        resources.publish(control)
                        announced = current.revision
                    }
                })
                if (resources.allows(snapshot)) failedRevision = snapshot.revision
            } catch (failure: IOException) {
                if (startingAudio || !negotiated || events.isClosed) throw failure
                if (resources.allows(snapshot)) {
                    ProjectionLog.failure("主屏视频流结束，保留控制会话等待恢复", failure)
                    failedRevision = snapshot.revision
                }
            } finally {
                video?.let {
                    resources.detach(it)
                    sockets.release(it)
                }
                if (negotiated && !events.isClosed) {
                    // Close only the type-110 stream. Audio and the session RECORD remain active.
                    val stream = NSDictionary().apply { put("type", MAIN_SCREEN); put("uuid", uuid) }
                    val body = NSDictionary().apply { put("streams", NSArray(stream)) }
                    request(control, "TEARDOWN", target, body)
                    ProjectionLog.event("已释放主屏视频流 · 保留控制和音频")
                }
            }
        }
        if (events.isClosed && keepRunning()) throw IOException("CarPlay 事件通道已关闭")
        return "投屏已结束"
    }

    private fun nextVideoConnectionId(): Long = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)

    private fun screenStream(uuid: String, connectionId: Long, latency: Int): NSDictionary {
        val stream = NSDictionary().apply {
            put("type", MAIN_SCREEN)
            put("streamConnectionID", connectionId)
            put("uuid", uuid)
            put("latencyMs", latency)
        }
        return NSDictionary().apply { put("streams", NSArray(stream)) }
    }

    private fun setupScreen(control: RtspControl, target: String, uuid: String, id: Long, latency: Int): Int =
        screenPort(control.request("SETUP", target, plistHeaders, BinaryPropertyListWriter.writeToArray(screenStream(uuid, id, latency))))

    private fun screenPort(response: RtspResponse): Int {
        if (response.status !in 200..299) throw ProtocolException("主屏 SETUP 失败（RTSP ${response.status}）")
        val streams = parse(response.body).objectForKey("streams") as? NSArray
        val screen = streams?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
            (it.objectForKey("type") as? NSNumber)?.takeIf { value -> value.isInteger }?.longValue() == MAIN_SCREEN.toLong()
        } ?: throw ProtocolException("接收端未返回主屏流")
        return port(screen, "dataPort")
    }

    internal fun startHeartbeat(
        control: RtspControl, sockets: CarPlaySockets, video: Socket, host: InetAddress, keepAlivePort: Int?,
        mguHealth: MguWifiHealth? = null,
    ): Thread = Thread({
        try {
            val ping = keepAlivePort?.let { DatagramPacket(ByteArray(4), 4, host, it) }
            val random = if (ping != null) SecureRandom() else null
            val interval = if (ping != null || mguHealth != null) 1_000L else 3_000L
            (ping?.let { sockets.openDatagram() }).use { udp ->
                var nextPing = System.nanoTime()
                var feedback = ping != null || mguHealth != null
                if (!feedback) Thread.sleep(interval)
                while (!video.isClosed && !Thread.currentThread().isInterrupted) {
                    val now = System.nanoTime()
                    if (udp != null && ping != null && random != null && now - nextPing >= 0L) {
                        random.nextBytes(ping.data)
                        udp.send(ping)
                        nextPing = now + 5_000_000_000L
                    }
                    val reply = if (feedback) control.request("POST", "/feedback") else control.request("OPTIONS", "*")
                    if (feedback) mguHealth?.feedback(reply.status)
                    if (feedback && (reply.status == 404 || reply.status == 405 || reply.status == 501)) {
                        feedback = false
                    } else if (reply.status in 500..599) {
                        throw ProtocolException("CarPlay heartbeat returned ${reply.status}")
                    }
                    Thread.sleep(interval)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: IOException) {
            if (!video.isClosed) {
                ProjectionLog.failure("CarPlay 控制通道心跳失败", failure)
                sockets.close()
            }
        }
    }, "CarPlay connection heartbeat").apply { isDaemon = true; start() }

    private fun openAudio(
        info: NSDictionary, host: InetAddress, control: RtspControl, keys: CarPlaySessionKeys,
        sockets: CarPlaySockets, preferences: ProjectionPreferences,
        onAudio: (Boolean, CarPlayAudioInfo) -> Unit, target: String, navigation: Boolean,
    ): CarPlayMediaAudio? {
        val audioType = if (navigation) "alert" else "media"
        val formats = info.objectForKey("audioFormats") as? NSArray ?: run {
            ProjectionLog.event("车机未提供音频格式 · stream=$audioType")
            return null
        }
        val media = formats.array.filterIsInstance<NSDictionary>().filter {
            (it.objectForKey("audioType") as? NSString)?.content == audioType
        }
        fun mask(type: Int): Long = media.asSequence()
            .filter { (it.objectForKey("type") as? NSNumber)?.takeIf { value -> value.isInteger }?.longValue() == type.toLong() }
            .mapNotNull { (it.objectForKey("audioOutputFormats") as? NSNumber)?.takeIf { value -> value.isInteger }?.longValue() }
            .fold(0L) { combined, formats -> combined or formats }
        val aacMask = mask(CarPlayMediaAudio.AAC_TYPE)
        val pcmMask = mask(CarPlayMediaAudio.PCM_TYPE)
        val mediaAac = when {
            aacMask and 0x800000L != 0L -> 0x800000 to 48_000
            aacMask and 0x400000L != 0L -> 0x400000 to 44_100
            else -> null
        }
        val mediaPcm = when {
            pcmMask and 0x800L != 0L -> 0x800 to 44_100
            pcmMask and 0x8000L != 0L -> 0x8000 to 48_000
            else -> null
        }
        val candidates = if (navigation) listOfNotNull(when {
            pcmMask and 0x4000L != 0L -> 0x4000 to 48_000
            pcmMask and 0x400L != 0L -> 0x400 to 44_100
            pcmMask and 0x10L != 0L -> 0x10 to 16_000
            else -> null
        }) else {
            if (preferences.audioPreference == AudioPreference.PCM) listOfNotNull(mediaPcm, mediaAac)
            else listOfNotNull(mediaAac, mediaPcm)
        }
        if (candidates.isEmpty()) {
            ProjectionLog.event("无可用音频格式 · stream=$audioType AAC=0x${aacMask.toString(16)} PCM=0x${pcmMask.toString(16)}")
            return null
        }
        for ((format, rate) in candidates) {
            val aac = format >= 0x400000
            val bufferMs = if (navigation) preferences.navigationBufferMs ?: 100
                else preferences.mediaBufferMs ?: if (aac) 350 else 120
            val channels = if (navigation) 1 else 2
            val type = if (aac) CarPlayMediaAudio.AAC_TYPE else CarPlayMediaAudio.PCM_TYPE
            val id = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)
            val stream = NSDictionary().apply {
                put("type", type)
                put("audioType", audioType)
                put("audioFormat", format)
                put("audioLatencyMs", bufferMs)
                put("streamConnectionID", id)
            }
            val body = NSDictionary().apply {
                put("streams", NSArray(1).apply { setValue(0, stream) })
            }
            val result = control.request("SETUP", target, plistHeaders, BinaryPropertyListWriter.writeToArray(body))
            if (result.status in FORMAT_REJECTIONS || navigation && result.status in 400..499 && result.status !in 401..403) {
                ProjectionLog.event("车机拒绝可选音频 · stream=$audioType format=$format · RTSP ${result.status}")
                continue
            }
            if (result.status !in 200..299) throw ProtocolException("音频 SETUP 失败（RTSP ${result.status}）")
            val reply = parse(result.body)
            val streams = reply.objectForKey("streams") as? NSArray
            val response = streams?.array?.filterIsInstance<NSDictionary>()?.firstOrNull {
                val streamId = it.objectForKey("streamConnectionID")
                (it.objectForKey("type") as? NSNumber)?.takeIf { value -> value.isInteger }?.longValue() == type.toLong() &&
                    (streamId == null || streamId is NSNumber && streamId.isInteger && streamId.longValue() == id)
            } ?: run {
                ProjectionLog.event("车机 SETUP 未返回匹配音频流 · stream=$audioType format=$format")
                return null
            }
            val dataPort = port(response, "dataPort")
            return CarPlayMediaAudio(
                sockets.openDatagram(), host, dataPort, keys.video(id), rate, aac,
                stream = if (navigation) 2 else 1, outputChannels = channels,
            ).also {
                val codec = if (aac) "AAC-LC" else "PCM"
                ProjectionLog.event("音频协商成功 · $audioType · $codec · $rate Hz · ${channels} 声道 · ${bufferMs} ms")
                onAudio(navigation, CarPlayAudioInfo(codec, rate, channels, bufferMs, dataPort))
            }
        }
        return null
    }

    internal fun negotiateVideoCodec(
        control: RtspControl, target: String, session: NSDictionary, offerHevc: Boolean,
    ): Pair<NSDictionary, VideoCodec> {
        var offered = offerHevc
        if (offered) session.put("features", NSArray(NSString("hevc")))
        fun setup() = control.request("SETUP", target, plistHeaders, BinaryPropertyListWriter.writeToArray(session))
        var response = setup()
        if (offered && response.status in FORMAT_REJECTIONS) {
            session.remove("features")
            offered = false
            ProjectionLog.event("车机拒绝 HEVC 会话提议，改用 H.264")
            response = setup()
        }
        if (response.status !in 200..299) throw ProtocolException("SETUP 失败（RTSP ${response.status}）")
        val reply = parse(response.body)
        val enabled = (reply.objectForKey("enabledFeatures") as? NSArray)?.array.orEmpty()
            .filterIsInstance<NSString>().any { it.content == "hevc" }
        return reply to if (offered && enabled) VideoCodec.HEVC else VideoCodec.H264
    }

    private fun sendVideo(
        video: Socket,
        key: ByteArray,
        negotiatedCodec: VideoCodec,
        onStreamStarted: () -> Unit,
        onStage: (String) -> Unit,
        onTick: () -> Unit = {},
    ): String {
        val feed = CarWithVideoFeed.subscribe()
        val writeStartedNs = AtomicLong()
        val watchdog = Thread({
            try {
                while (!video.isClosed) {
                    Thread.sleep(250)
                    val started = writeStartedNs.get()
                    if (started != 0L && System.nanoTime() - started >= VIDEO_WRITE_TIMEOUT_MS * 1_000_000 &&
                        writeStartedNs.compareAndSet(started, 0)
                    ) {
                        // Closing the socket interrupts a write even when the control channel still responds.
                        runCatching { video.close() }
                        ProjectionLog.event("CarPlay 视频写入超时，已关闭视频连接")
                        break
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "CarPlay video watchdog").apply { isDaemon = true }
        try {
            watchdog.start()
            CarlifeConnectService.requestKeyFrame()
            val writer = CarPlayVideoWriter(video.getOutputStream(), key)
            var frames = 0L
            var keyFrameDeadline = Long.MAX_VALUE
            var sentBytes = 0L
            var longestWriteMs = 0L
            var nextKeepAlive = SystemClock.elapsedRealtime() + 1_000
            var nextReport = SystemClock.elapsedRealtime() + 5_000
            var nextPersistentReport = SystemClock.elapsedRealtime() + 30_000
            var firstPtsUs = 0L
            var firstNtp = 0L
            var lastPtsUs = 0L
            var lastCodec: VideoCodec? = null
            while (!video.isClosed && CarlifeConnectService.state.value.active) {
                onTick()
                val now = SystemClock.elapsedRealtime()
                if (frames == 0L && CarlifeConnectService.state.value.phase == CapturePhase.RECEIVING) {
                    if (keyFrameDeadline == Long.MAX_VALUE) keyFrameDeadline = now + FIRST_KEY_FRAME_TIMEOUT_MS
                    if (now >= keyFrameDeadline) throw ProtocolException("CarWith 未提供可发送的视频关键帧")
                }
                if (now >= nextKeepAlive) {
                    writeStartedNs.set(System.nanoTime())
                    try {
                        writer.writeKeepAlive()
                    } finally {
                        writeStartedNs.set(0)
                    }
                    nextKeepAlive = now + 1_000
                }
                if (now >= nextReport) {
                    val (encoded, dropped) = feed.counts()
                    android.util.Log.i("CarNatVideo", "encoded=$encoded sent=$frames dropped=$dropped payloadBytes=$sentBytes maxWriteMs=$longestWriteMs")
                    if (now >= nextPersistentReport) {
                        ProjectionLog.event("视频统计 · encoded=$encoded sent=$frames dropped=$dropped bytes=$sentBytes maxWriteMs=$longestWriteMs")
                        nextPersistentReport = now + 30_000
                    }
                    nextReport = now + 5_000
                }
                val frame = feed.poll(200) ?: continue
                if (frame.codec == VideoCodec.HEVC && negotiatedCodec != VideoCodec.HEVC) continue
                val writeStarted = SystemClock.elapsedRealtime()
                if (frames == 0L) {
                    firstPtsUs = frame.ptsUs
                    firstNtp = CarPlayTiming.now()
                }
                if (lastCodec != null && lastCodec != frame.codec && frame.ptsUs - firstPtsUs <= lastPtsUs) {
                    firstPtsUs = frame.ptsUs - lastPtsUs - 1
                }
                lastCodec = frame.codec
                val elapsedUs = (frame.ptsUs - firstPtsUs).coerceAtLeast(lastPtsUs)
                lastPtsUs = elapsedUs
                val offset = ((elapsedUs / 1_000_000) shl 32) +
                    (((elapsedUs % 1_000_000) shl 32) / 1_000_000)
                writeStartedNs.set(System.nanoTime())
                try {
                    frame.configuration?.let { writer.writeConfiguration(it, frame.width, frame.height, frame.codec) }
                    writer.writeAccessUnit(frame.payload, firstNtp + offset)
                } finally {
                    writeStartedNs.set(0)
                }
                frames++
                sentBytes += frame.payload.size
                longestWriteMs = maxOf(longestWriteMs, SystemClock.elapsedRealtime() - writeStarted)
                if (frames == 1L) {
                    onStreamStarted()
                    onStage("正在投屏 · 已发送 CarWith 首个视频关键帧")
                }
            }
            if (frames == 0L) throw ProtocolException("CarWith 未提供可发送的视频关键帧")
            return "投屏已结束 · 已发送 $frames 帧 CarWith 视频"
        } finally {
            watchdog.interrupt()
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

    private fun parse(body: ByteArray): NSDictionary = CarPlayPlist.readDictionary(body)

    private fun port(body: NSDictionary, key: String): Int {
        val number = body.objectForKey(key) as? NSNumber
        val value = number?.takeIf { it.isInteger }?.longValue()
        return value?.takeIf { it in 1..65535 }?.toInt()
            ?: throw ProtocolException("接收端未返回有效的 $key")
    }

    private fun connect(sockets: CarPlaySockets, host: InetAddress, port: Int): Socket {
        val socket = sockets.open()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 5_000)
            return socket
        } catch (failure: Exception) {
            sockets.release(socket)
            throw failure
        }
    }
}
