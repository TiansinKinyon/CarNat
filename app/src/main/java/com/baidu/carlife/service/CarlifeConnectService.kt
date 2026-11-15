package com.baidu.carlife.service

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.baidu.carlife.mixing.CastConfig
import com.baidu.carlife.mixing.MusicInfo
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback
import com.baidu.carlife.mixing.aidl.ICarLifeCastManager
import com.baidu.carlife.mixing.aidl.IRemoteAudioListener
import com.baidu.carlife.mixing.aidl.IRemoteKeyListener
import com.baidu.carlife.mixing.aidl.IRemoteTouchListener
import com.baidu.carlife.mixing.aidl.ISystemAudioCallback
import com.baidu.carlife.xiaomi.MainActivity
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.carplay.AvcPayload
import com.baidu.carlife.xiaomi.carplay.CarPlayMediaAudio
import com.baidu.carlife.xiaomi.carplay.CarPlayResolution
import com.baidu.carlife.xiaomi.carplay.CarWithVideoFeed
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import com.baidu.carlife.xiaomi.carplay.BluetoothReceiverProbe
import com.baidu.carlife.xiaomi.carplay.ReceiverDiagnostics
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
import com.baidu.carlife.xiaomi.carplay.missingReceiverPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CarlifeConnectService : Service() {
    private val entryBinder = Binder()
    private val projectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiverProbe: BluetoothReceiverProbe? = null
    private var receiverJob: Job? = null
    private var stopping = false

    @android.annotation.SuppressLint("MissingPermission")
    private fun startReceiver(capture: CaptureSession, expectedResolution: CarPlayResolution? = null) {
        val readResolutionOnly = expectedResolution == null
        val settings = ReceiverSettings.load(this)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (!settings.valid || adapter == null || missingReceiverPermissions(this).isNotEmpty()) {
            worker.post { capture.fail("请检查接收端设置和附近设备权限") }
            return
        }
        val probe = BluetoothReceiverProbe(getSystemService(ConnectivityManager::class.java))
        receiverProbe = probe
        val timeout = projectionScope.launch {
            delay(RECEIVER_CONNECT_TIMEOUT_MS)
            stopReceiver()
            worker.post { capture.fail("等待 CarPlay 接收端超时") }
        }
        receiverJob = projectionScope.launch {
            var streamed = false
            var probedResolution: CarPlayResolution? = null
            try {
                withContext(Dispatchers.IO) {
                    probe.run(
                        adapter, settings.name, settings.networkPairingCode, settings.address,
                        onStreamStarted = {
                            streamed = true
                            timeout.cancel()
                            projectionScope.launch {
                                if (receiverProbe === probe) dismissConnectingNotification()
                            }
                        },
                        receiverAddress = settings.receiverAddress,
                        readResolutionOnly = readResolutionOnly,
                        onResolution = { resolution ->
                            if (expectedResolution == null) {
                                probedResolution = resolution
                            } else {
                                val changed = !expectedResolution.matchesEncoderSettings(resolution)
                                projectionScope.launch {
                                    if (receiverProbe === probe) {
                                        mutableState.update { it.copy(receiverDisplayChanged = changed) }
                                    }
                                }
                                if (changed) Log.i(TAG, "Receiver display changed; keeping the active CarWith capture")
                            }
                        },
                        onDiagnostics = { diagnostics ->
                            projectionScope.launch {
                                if (receiverProbe === probe) mutableReceiverDiagnostics.value = diagnostics
                            }
                        },
                    ) { stage ->
                        Log.i("CarNatIap2", stage)
                        projectionScope.launch {
                            if (receiverProbe === probe) mutableState.update { it.copy(receiverStatus = stage) }
                        }
                    }
                }
                if (!streamed && probedResolution == null && receiverProbe === probe) {
                    throw IOException("CarPlay 接收端在画面开始前断开")
                }
            } catch (failure: Exception) {
                probedResolution = null
                if (receiverProbe === probe) {
                    Log.i("CarNatIap2", "Receiver session ended", failure)
                    val message = failure.message ?: "投屏连接失败"
                    if (!streamed) {
                        worker.post { capture.fail(message) }
                    } else {
                        mutableState.update { it.copy(receiverStatus = message) }
                    }
                }
            } finally {
                timeout.cancel()
                if (receiverProbe === probe) {
                    receiverProbe = null
                    dismissConnectingNotification()
                    mutableReceiverDiagnostics.value = ReceiverDiagnostics()
                    probedResolution?.let(::prepareCarWithLaunch)
                }
            }
        }
    }

    private fun stopReceiver() {
        dismissConnectingNotification()
        receiverProbe?.close()
        receiverProbe = null
        launchResolution = null
        main.removeCallbacks(armTimeout)
        mutableReceiverDiagnostics.value = ReceiverDiagnostics()
        receiverJob?.cancel()
        receiverJob = null
    }
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private var session: CaptureSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val armTimeout = Runnable {
        val message = when (mutableState.value.phase) {
            CapturePhase.READY_TO_START_CAR_WITH -> "等待打开 CarWith 超时"
            CapturePhase.ARMED -> "CarWith 未启动 CarLife 组件"
            else -> return@Runnable
        }
        failCarWithStartup(message)
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("CarWith capture").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent): IBinder = entryBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopToken) {
            clearLaunchTicket(this)
            stopping = true
            stopReceiver()
            mutableState.update {
                when (it.phase) {
                    CapturePhase.WAITING_FOR_RECEIVER, CapturePhase.READY_TO_START_CAR_WITH, CapturePhase.ARMED ->
                        it.copy(phase = CapturePhase.STOPPED, receiverStatus = null)
                    else -> it
                }
            }
            worker.post {
                session?.finish()
                session = null
                main.post {
                    stopping = false
                    mutableState.update {
                        it.copy(phase = CapturePhase.STOPPED, receiverStatus = null, receiverDisplayChanged = false)
                    }
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == diagnosticToken) {
            if (!stopping && !mutableState.value.active) {
                clearLaunchTicket(this)
                startForeground(NOTIFICATION_ID, diagnosticNotification())
                mutableState.value = CaptureStatus(phase = CapturePhase.WAITING_FOR_RECEIVER)
                val capture = CaptureSession()
                worker.post { session = capture }
                startReceiver(capture)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == launchFailedToken) {
            if (mutableState.value.phase == CapturePhase.ARMED) {
                failCarWithStartup(intent.getStringExtra("error") ?: "无法打开 CarWith")
            }
            return START_NOT_STICKY
        }

        // The exported service accepts only the fixed CarWith endpoint, during a local opt-in.
        val carWithRequest = intent != null &&
            intent.getStringExtra("bindPackageName") == CAR_WITH_SERVICE.packageName &&
            intent.getStringExtra("bindClassName") == CAR_WITH_SERVICE.className
        val phase = mutableState.value.phase
        val resolution = if (carWithRequest && !stopping && (phase == CapturePhase.ARMED || !phase.isActive)) {
            consumeLaunchTicket(this)
        } else null
        if (intent != null && resolution != null) {
            startForeground(NOTIFICATION_ID, diagnosticNotification())
            val connectionType = intent.getIntExtra("usb_type", -1)
            Log.i(TAG, "CarLife start received, usb_type=$connectionType")
            showConnectingNotification()
            beginCapture(connectionType, resolution)
        } else if (!mutableState.value.active) {
            // Even a declined external start must satisfy startForegroundService's contract.
            startForeground(NOTIFICATION_ID, diagnosticNotification())
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun prepareCarWithLaunch(resolution: CarPlayResolution) {
        launchResolution = resolution
        main.postDelayed(armTimeout, CAR_WITH_START_TIMEOUT_MS)
        mutableState.update {
            it.copy(
                phase = CapturePhase.READY_TO_START_CAR_WITH,
                receiverStatus = "主屏分辨率已确认，正在准备启动 CarWith",
            )
        }
    }

    private fun beginCapture(connectionType: Int, resolution: CarPlayResolution) {
        val capture = CaptureSession(resolution)
        main.removeCallbacks(armTimeout)
        mutableState.update {
            it.copy(
                phase = CapturePhase.BINDING,
                connectionType = connectionType,
                receiverStatus = "正在准备 CarWith 画面",
            )
        }
        worker.post {
            session = capture
            capture.bind()
        }
        startReceiver(capture, expectedResolution = resolution)
    }

    private fun failCarWithStartup(message: String) {
        clearLaunchTicket(this)
        stopReceiver()
        worker.post { session?.fail(message) }
    }

    override fun onDestroy() {
        stopReceiver()
        projectionScope.cancel()
        worker.post {
            session?.finish()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    private fun showConnectingNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CONNECTING_CHANNEL_ID, "连接提醒", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!notifications.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(this, CONNECTING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("CarNat 正在连接")
            .setContentText("正在连接车机并准备 CarWith 投屏画面")
            .setContentIntent(openCarNatIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(RECEIVER_CONNECT_TIMEOUT_MS)
            .build()
        try {
            notifications.notify(CONNECTING_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification access can be revoked between the permission check and posting.
        }
    }

    private fun dismissConnectingNotification() {
        getSystemService(NotificationManager::class.java).cancel(CONNECTING_NOTIFICATION_ID)
    }

    private fun openCarNatIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun diagnosticNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CarWith 投屏诊断", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CarNat")
            .setContentText("CarWith 投屏诊断正在运行")
            .setContentIntent(openCarNatIntent())
            .setOngoing(true)
            .build()
    }

    private inner class CaptureSession(private val resolution: CarPlayResolution? = null) : ServiceConnection {
        private var manager: ICarLifeCastManager? = null
        private var codec: MediaCodec? = null
        private var surface: Surface? = null
        private var bound = false
        private var closed = false
        private var frames = 0L
        private var bytes = 0L
        private var sampledAtMs = 0L
        private var sampledFrames = 0L
        private var sampledBytes = 0L
        private val sampleOutput = object : Runnable {
            override fun run() {
                if (closed) return
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - sampledAtMs).coerceAtLeast(1)
                val fps = (frames - sampledFrames) * 1_000.0 / elapsed
                val megabitsPerSecond = (bytes - sampledBytes) * 0.008 / elapsed
                sampledAtMs = now
                sampledFrames = frames
                sampledBytes = bytes
                mutableState.update {
                    it.copy(frames = frames, bytes = bytes, outputFps = fps, outputMbps = megabitsPerSecond)
                }
                worker.postDelayed(this, OUTPUT_SAMPLE_MS)
            }
        }
        private var avcConfiguration: ByteArray? = null
        private var inspectedKeyFrame = false
        private val requestSyncFrame: () -> Unit = {
            worker.post {
                if (!closed) {
                    try {
                        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } catch (error: IllegalStateException) {
                        Log.w(TAG, "Encoder sync request failed", error)
                    }
                }
            }
        }
        private var touchListener: IRemoteTouchListener? = null
        private var touchDownTime: Long? = null
        private var keyListener: IRemoteKeyListener? = null
        private val contacts = mutableListOf<RemoteContact>()
        private val touchSink: (List<RemoteContact>, Boolean) -> Unit = { report, cancel ->
            worker.post { sendTouch(report, cancel) }
        }
        private val keySink: (Int) -> Unit = { code ->
            worker.post {
                val listener = keyListener
                if (!closed && listener != null) {
                    val now = SystemClock.uptimeMillis()
                    try {
                        val accepted = listener.onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                        listener.onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
                        Log.d(TAG, "CarWith key code=$code accepted=$accepted")
                    } catch (error: RemoteException) {
                        Log.w(TAG, "CarWith key listener disconnected", error)
                    }
                }
            }
        }

        private fun sendTouch(report: List<RemoteContact>, cancel: Boolean) {
            val listener = touchListener ?: return
            val displayResolution = resolution ?: return
            if (closed || mutableState.value.displayId == null) return
            val now = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                val properties = contacts.map {
                    MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }.toTypedArray()
                val coordinates = contacts.map {
                    MotionEvent.PointerCoords().apply {
                        x = it.x * (displayResolution.width - 1)
                        y = it.y * (displayResolution.height - 1)
                        pressure = 1f
                        size = 1f
                    }
                }.toTypedArray()
                val event = MotionEvent.obtain(touchDownTime ?: now, now, action, contacts.size,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    val accepted = listener.onTouchEvent(event)
                    if (action != MotionEvent.ACTION_MOVE) Log.d(TAG, "CarWith touch action=$action pointers=${contacts.size} accepted=$accepted")
                } finally {
                    event.recycle()
                }
            }
            try {
                if (cancel) {
                    if (contacts.isNotEmpty()) dispatch(MotionEvent.ACTION_CANCEL)
                    contacts.clear()
                    touchDownTime = null
                    return
                }
                var changed = false
                for (index in contacts.indices.reversed()) {
                    val update = report.firstOrNull { it.id == contacts[index].id }
                    if (update != null) contacts[index] = update
                    if (update?.down != true) {
                        dispatch(if (contacts.size == 1) MotionEvent.ACTION_UP else
                            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                        contacts.removeAt(index)
                        changed = true
                    }
                }
                for (contact in report) {
                    if (!contact.down || contacts.any { it.id == contact.id }) continue
                    contacts.add(contact)
                    if (contacts.size == 1) touchDownTime = now
                    dispatch(if (contacts.size == 1) MotionEvent.ACTION_DOWN else
                        MotionEvent.ACTION_POINTER_DOWN or (contacts.lastIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    changed = true
                }
                if (contacts.isEmpty()) touchDownTime = null
                else if (!changed) dispatch(MotionEvent.ACTION_MOVE)
            } catch (error: RemoteException) {
                contacts.clear()
                touchDownTime = null
                Log.w(TAG, "CarWith touch listener disconnected", error)
            }
        }

        private val timeout = Runnable {
            val message = when {
                manager == null -> "Timed out binding the CarWith cast service"
                mutableState.value.displayId == null -> "CarWith did not return onCastPrepared"
                else -> "CarWith prepared a display but no encoded frame arrived"
            }
            fail(message)
        }

        private val audioCallback = object : ISystemAudioCallback.Stub() {
            override fun onAudioStreamReady(data: ByteArray?, stream: Int, sampleRate: Int, channels: Int, format: Int) =
                CarPlayMediaAudio.offer(data, stream, sampleRate, channels, format)
            override fun onAudioStateChange(stream: Int, state: Int) {
                Log.d(TAG, "CarWith audio stream=$stream state=$state")
            }
            override fun onAudioStateChangeWithParams(stream: Int, state: Int, sampleRate: Int, channels: Int, format: Int) = Unit
        }

        private val callback = object : ICarLifeCastCallback.Stub() {
            override fun onCastPrepared(displayId: Int) {
                worker.post {
                    if (!closed) {
                        Log.i(TAG, "onCastPrepared displayId=$displayId")
                        mutableState.update { it.copy(displayId = displayId) }
                    }
                }
            }

            override fun onServiceDestroy() {
                worker.post { if (!closed) fail("CarWith ended the cast") }
            }

            override fun onServiceReady() { Log.i(TAG, "onServiceReady") }
            override fun addTouchListener(listener: IRemoteTouchListener?) {
                if (listener == null) return
                worker.post {
                    if (!closed) {
                        touchListener = listener
                        remoteTouchSink = touchSink
                    }
                }
            }

            override fun removeTouchListener(listener: IRemoteTouchListener?) {
                worker.post {
                    if (listener?.asBinder() == touchListener?.asBinder()) {
                        touchListener = null
                        contacts.clear()
                        touchDownTime = null
                        if (remoteTouchSink === touchSink) remoteTouchSink = null
                    }
                }
            }
            override fun addKeyListener(listener: IRemoteKeyListener?) {
                if (listener == null) return
                worker.post {
                    if (!closed) {
                        keyListener = listener
                        remoteKeySink = keySink
                    }
                }
            }

            override fun removeKeyListener(listener: IRemoteKeyListener?) {
                worker.post {
                    if (listener?.asBinder() == keyListener?.asBinder()) {
                        keyListener = null
                        if (remoteKeySink === keySink) remoteKeySink = null
                    }
                }
            }
            override fun sendMusicInfo(info: MusicInfo?) = Unit
            override fun notifyMusicProgress(positionMs: Long) = Unit
            override fun invokeMethod(intent: Intent?): Bundle = Bundle().apply {
                if (intent?.action == "carlife_music_sample_rate") {
                    putInt("music_sample_rate", 44_100)
                    Log.i(TAG, "Reported CarWith music sample rate: 44100 Hz")
                }
            }
            override fun notifyAudioState(state: Int) = Unit
            override fun addAudioListener(listener: IRemoteAudioListener?) = Unit
            override fun removeAudioListener(listener: IRemoteAudioListener?) = Unit
        }

        fun bind() {
            if (closed) return
            try {
                bound = bindService(Intent().setComponent(CAR_WITH_SERVICE), this, BIND_AUTO_CREATE)
                if (bound) worker.postDelayed(timeout, 12_000)
                else fail("CarWith cast service is unavailable")
            } catch (error: SecurityException) {
                fail(error.message ?: "CarWith denied the service binding")
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post {
                if (closed) return@post
                manager = ICarLifeCastManager.Stub.asInterface(binder)
                worker.removeCallbacks(timeout)
                Log.i(TAG, "Bound to CarWith")
                prepareCast()
            }
        }

        private fun prepareCast() {
            val remote = manager ?: return
            val size = resolution ?: return
            if (codec != null) return
            try {
                val inputSurface = startEncoder(size)
                mutableState.update { it.copy(phase = CapturePhase.WAITING_FOR_FRAMES) }
                worker.postDelayed(timeout, 12_000)
                remote.setCastCallback(callback)
                remote.setSystemAudioCallback(audioCallback)
                remote.prepareCast(CastConfig(size.width, size.height, DPI, inputSurface))
                Log.i(TAG, "CarWith prepareCast: ${size.width}×${size.height}")
            } catch (error: Exception) {
                Log.w(TAG, "CarWith setup failed for ${size.width}×${size.height}", error)
                fail("无法以 ${size.width}×${size.height} 启动 CarWith 投屏")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith disconnected") }
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith returned no cast interface") }
        }

        private fun startEncoder(size: CarPlayResolution): Surface {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, size.frameRate)
            if (Build.VERSION.SDK_INT >= 29) {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, size.frameRate.toFloat())
            }
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (closed) return
                    val width = format.visibleDimension(MediaFormat.KEY_WIDTH, MediaFormat.KEY_CROP_LEFT, MediaFormat.KEY_CROP_RIGHT)
                    val height = format.visibleDimension(MediaFormat.KEY_HEIGHT, MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_BOTTOM)
                    mutableState.update { it.copy(outputWidth = width, outputHeight = height) }
                    val sps = format.getByteBuffer("csd-0") ?: return
                    readConfiguration(sps, format.getByteBuffer("csd-1"))
                }

                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    if (!closed) fail(error.diagnosticInfo)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (closed) return
                    try {
                        try {
                            inspectOutput(codec, index, info)
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    } catch (error: IllegalStateException) {
                        fail(error.message ?: "Could not release encoder output")
                        return
                    }
                    if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                    frames++
                    bytes += info.size
                    if (frames == 1L) {
                        Log.i(TAG, "First H.264 frame, ${info.size} bytes")
                        worker.removeCallbacks(timeout)
                        mutableState.update { it.copy(phase = CapturePhase.RECEIVING, frames = frames, bytes = bytes) }
                    }
                }
            }, worker)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            surface = inputSurface
            encoder.start()
            sampledAtMs = SystemClock.elapsedRealtime()
            worker.postDelayed(sampleOutput, OUTPUT_SAMPLE_MS)
            keyFrameSink = requestSyncFrame
            return inputSurface
        }

        private fun inspectOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (info.size <= 0) return
            val configBuffer = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

            val data = codec.getOutputBuffer(index)?.duplicate() ?: return
            data.limit(info.offset + info.size)
            data.position(info.offset)
            if (configBuffer) {
                if (avcConfiguration == null) readConfiguration(data, null)
                return
            }
            val configuration = avcConfiguration ?: return
            val size = checkNotNull(resolution)
            CarWithVideoFeed.offer(data.slice(), configuration, size.width, size.height, keyFrame, info.presentationTimeUs)
            if (inspectedKeyFrame || !keyFrame) return
            try {
                val accessUnit = AvcPayload.accessUnit(data)
                inspectedKeyFrame = true
                mutableState.update {
                    it.copy(avcConfigBytes = configuration.size, avcKeyFrameBytes = accessUnit.size)
                }
                Log.i(TAG, "CarPlay video input: avcC=${configuration.size}, key frame=${accessUnit.size}")
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Could not format AVC key frame: ${error.message}")
            }
        }

        private fun readConfiguration(sps: ByteBuffer, pps: ByteBuffer?) {
            try {
                avcConfiguration = AvcPayload.configuration(sps, pps)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Could not format AVC configuration: ${error.message}")
            }
        }

        private fun MediaFormat.visibleDimension(size: String, start: String, end: String): Int? {
            if (containsKey(start) && containsKey(end)) {
                return (getInteger(end) - getInteger(start) + 1).takeIf { it > 0 }
            }
            return if (containsKey(size)) getInteger(size).takeIf { it > 0 } else null
        }

        fun fail(message: String) {
            if (closed) return
            Log.w(TAG, message)
            mutableState.update { it.copy(phase = CapturePhase.FAILED, error = message) }
            finish()
            stopSelf()
        }

        fun finish() {
            if (closed) return
            closed = true
            if (remoteTouchSink === touchSink) remoteTouchSink = null
            if (keyFrameSink === requestSyncFrame) keyFrameSink = null
            touchListener = null
            if (remoteKeySink === keySink) remoteKeySink = null
            keyListener = null
            worker.removeCallbacks(timeout)
            worker.removeCallbacks(sampleOutput)
            try {
                manager?.stopCast()
            } catch (_: RemoteException) {
                // CarWith may already have exited.
            } finally {
                if (bound) unbindService(this)
                runCatching { codec?.release() }
                surface?.release()
                mutableState.update {
                    it.copy(
                        phase = if (it.phase == CapturePhase.FAILED) CapturePhase.FAILED else CapturePhase.STOPPED,
                        frames = frames, bytes = bytes, outputFps = null, outputMbps = null,
                        outputWidth = null, outputHeight = null, displayId = null, receiverDisplayChanged = false,
                    )
                }
            }
        }
    }

    companion object {
        private val CAR_WITH_SERVICE = ComponentName(
            "com.miui.carlink",
            "com.xiaomi.ucar.carlife.CarlifeConnService",
        )
        private val diagnosticToken = UUID.randomUUID().toString()
        private val launchFailedToken = UUID.randomUUID().toString()
        private val stopToken = UUID.randomUUID().toString()
        private const val TAG = "CarNatCapture"
        private const val CHANNEL_ID = "carwith_diagnostics"
        private const val NOTIFICATION_ID = 1
        private const val CONNECTING_CHANNEL_ID = "carplay_connecting"
        private const val CONNECTING_NOTIFICATION_ID = 2
        private const val RECEIVER_CONNECT_TIMEOUT_MS = 100_000L
        private const val CAR_WITH_START_TIMEOUT_MS = 90_000L
        // CarWith force-stops the CarLife component before starting its service.
        private const val LAUNCH_TICKET = "pending_carwith_launch"
        private var launchResolution: CarPlayResolution? = null
        private const val DPI = 160
        private const val OUTPUT_SAMPLE_MS = 1_000L
        private val mutableReceiverDiagnostics = MutableStateFlow(ReceiverDiagnostics())
        internal val receiverDiagnostics = mutableReceiverDiagnostics.asStateFlow()
        private val mutableState = MutableStateFlow(CaptureStatus())
        val state = mutableState.asStateFlow()

        @Volatile
        private var remoteTouchSink: ((List<RemoteContact>, Boolean) -> Unit)? = null

        @Volatile
        private var keyFrameSink: (() -> Unit)? = null

        internal fun requestKeyFrame() {
            keyFrameSink?.invoke()
        }

        @Volatile
        private var remoteKeySink: ((Int) -> Unit)? = null

        internal fun remoteKey(code: Int) {
            remoteKeySink?.invoke(code)
        }

        internal fun remoteTouch(contacts: List<RemoteContact>, cancel: Boolean = false) {
            remoteTouchSink?.invoke(contacts, cancel)
        }

        fun startDiagnostic(context: Context) {
            val intent = Intent(context, CarlifeConnectService::class.java).setAction(diagnosticToken)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopDiagnostic(context: Context) {
            context.startService(Intent(context, CarlifeConnectService::class.java).setAction(stopToken))
        }

        internal fun claimCarWithLaunch(context: Context): Boolean {
            val awaitingLaunch = mutableState.value
            val resolution = launchResolution ?: return false
            if (awaitingLaunch.phase != CapturePhase.READY_TO_START_CAR_WITH) return false
            val receiver = ReceiverSettings.load(context)
            if (!context.getSharedPreferences(LAUNCH_TICKET, MODE_PRIVATE).edit()
                    .putLong("elapsed", SystemClock.elapsedRealtime())
                    .putLong("wall", System.currentTimeMillis())
                    .putInt("width", resolution.width)
                    .putInt("height", resolution.height)
                    .putInt("max_fps", resolution.maxFps ?: 0)
                    .putString("receiver", receiver.receiverAddress)
                    .commit()
            ) return false
            val armed = awaitingLaunch.copy(phase = CapturePhase.ARMED, receiverStatus = "正在启动 CarWith")
            if (mutableState.compareAndSet(awaitingLaunch, armed)) return true
            clearLaunchTicket(context)
            return false
        }

        private fun consumeLaunchTicket(context: Context): CarPlayResolution? {
            val ticket = context.getSharedPreferences(LAUNCH_TICKET, MODE_PRIVATE)
            val elapsedAge = SystemClock.elapsedRealtime() - ticket.getLong("elapsed", -CAR_WITH_START_TIMEOUT_MS)
            val wallAge = System.currentTimeMillis() - ticket.getLong("wall", 0)
            if (elapsedAge !in 0 until CAR_WITH_START_TIMEOUT_MS ||
                wallAge !in 0 until CAR_WITH_START_TIMEOUT_MS ||
                ticket.getString("receiver", null) != ReceiverSettings.load(context).receiverAddress
            ) return null
            val width = ticket.getInt("width", 0)
            val height = ticket.getInt("height", 0)
            val maxFps = ticket.getInt("max_fps", 0).takeIf { it > 0 }
            if (width <= 0 || height <= 0 || !clearLaunchTicket(context)) return null
            return CarPlayResolution(width, height, maxFps)
        }

        private fun clearLaunchTicket(context: Context): Boolean =
            context.getSharedPreferences(LAUNCH_TICKET, MODE_PRIVATE).edit().clear().commit()

        internal fun reportCarWithLaunchFailure(context: Context, message: String) {
            context.startService(
                Intent(context, CarlifeConnectService::class.java)
                    .setAction(launchFailedToken)
                    .putExtra("error", message),
            )
        }
    }
}

internal data class RemoteContact(val id: Int, val x: Float, val y: Float, val down: Boolean)

enum class CapturePhase(val isActive: Boolean = false) {
    IDLE, WAITING_FOR_RECEIVER(true), READY_TO_START_CAR_WITH(true), ARMED(true),
    BINDING(true), WAITING_FOR_FRAMES(true), RECEIVING(true), STOPPED, FAILED
}

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val connectionType: Int? = null,
    val displayId: Int? = null,
    val frames: Long = 0,
    val bytes: Long = 0,
    val avcConfigBytes: Int? = null,
    val avcKeyFrameBytes: Int? = null,
    val error: String? = null,
    val receiverStatus: String? = null,
    val receiverDisplayChanged: Boolean = false,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val outputFps: Double? = null,
    val outputMbps: Double? = null,
) {
    val active: Boolean
        get() = phase.isActive
}
