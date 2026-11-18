package com.baidu.carlife.service

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
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
import com.baidu.carlife.mixing.CastConfig
import com.baidu.carlife.mixing.MusicInfo
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback
import com.baidu.carlife.mixing.aidl.ICarLifeCastManager
import com.baidu.carlife.mixing.aidl.IRemoteAudioListener
import com.baidu.carlife.mixing.aidl.IRemoteKeyListener
import com.baidu.carlife.mixing.aidl.IRemoteTouchListener
import com.baidu.carlife.mixing.aidl.ISystemAudioCallback
import com.baidu.carlife.xiaomi.MainActivity
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.carplay.AvcPayload
import com.baidu.carlife.xiaomi.carplay.CarPlayMediaAudio
import com.baidu.carlife.xiaomi.carplay.CarPlayResolution
import com.baidu.carlife.xiaomi.carplay.CarWithVideoFeed
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import com.baidu.carlife.xiaomi.carplay.BluetoothReceiverProbe
import com.baidu.carlife.xiaomi.carplay.ProjectionPreferences
import com.baidu.carlife.xiaomi.carplay.ProjectionConnectionStatus
import com.baidu.carlife.xiaomi.carplay.ProjectionLinkStatus
import com.baidu.carlife.xiaomi.carplay.ReceiverDiagnostics
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
import com.baidu.carlife.xiaomi.carplay.ConnectionMode
import com.baidu.carlife.xiaomi.carplay.connectionStatus
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
    private var lastNotificationStatus: ProjectionConnectionStatus? = null
    private var stopping = false

    @android.annotation.SuppressLint("MissingPermission")
    private fun startReceiver(
        capture: CaptureSession,
        expectedResolution: CarPlayResolution? = null,
        fromCarWith: Boolean = false,
    ) {
        val readResolutionOnly = expectedResolution == null && !fromCarWith
        val preferences = ProjectionPreferences.load(this)
        val settings = ReceiverSettings.load(this)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val missingPermissions = missingReceiverPermissions(this)
        if ((!fromCarWith && !settings.valid) || adapter == null || missingPermissions.isNotEmpty()) {
            ProjectionLog.event("接收端前置检查失败 · settingsValid=${settings.valid} adapterPresent=${adapter != null} missingPermissions=$missingPermissions")
            worker.post { capture.fail("请检查接收端设置和附近设备权限") }
            return
        }
        val deviceId = settings.address.takeIf(ReceiverSettings::validAddress)
            ?: ConnectionMode.automaticDeviceId(this)
        val probe = BluetoothReceiverProbe(getSystemService(ConnectivityManager::class.java))
        receiverProbe = probe
        val timeout = projectionScope.launch {
            delay(RECEIVER_CONNECT_TIMEOUT_MS)
            ProjectionLog.event("等待 CarPlay 连接超时")
            stopReceiver()
            worker.post { capture.fail("等待 CarPlay 接收端超时") }
        }
        receiverJob = projectionScope.launch {
            var streamed = false
            var terminalError: String? = null
            var probedResolution: CarPlayResolution? = null
            var captureResolution = expectedResolution
            try {
                withContext(Dispatchers.IO) {
                    probe.run(
                        adapter, if (fromCarWith) "" else settings.name, settings.networkPairingCode, deviceId,
                        onStreamStarted = {
                            streamed = true
                            timeout.cancel()
                            projectionScope.launch {
                                if (receiverProbe === probe) {
                                    mutableState.update { it.copy(receiverConnection = ReceiverConnection.CONNECTED) }
                                }
                            }
                        },
                        onReconnecting = {
                            projectionScope.launch {
                                if (receiverProbe === probe) {
                                    mutableState.update {
                                        it.copy(
                                            receiverConnection = ReceiverConnection.PENDING,
                                            receiverStatus = "正在等待车机重新连接",
                                        )
                                    }
                                }
                            }
                        },
                        receiverAddress = if (fromCarWith) "" else settings.receiverAddress,
                        readResolutionOnly = readResolutionOnly,
                        preferences = preferences,
                        retryAfterDisconnect = !fromCarWith,
                        onResolution = { resolution ->
                            val currentResolution = captureResolution
                            if (readResolutionOnly) {
                                probedResolution = resolution
                            } else if (currentResolution == null) {
                                val chosen = preferences.applyTo(resolution)
                                captureResolution = chosen
                                projectionScope.launch {
                                    if (receiverProbe === probe && !stopping) bindCapture(capture, chosen)
                                }
                            } else {
                                val changed = preferences.manualDisplayMode == null &&
                                    !currentResolution.matchesEncoderSettings(preferences.applyTo(resolution))
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
                        ProjectionLog.stage(stage)
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
                    ProjectionLog.failure("车机会话结束", failure)
                    Log.i("CarNatIap2", "Receiver session ended", failure)
                    val message = failure.message ?: "投屏连接失败"
                    terminalError = message
                    if (!streamed || fromCarWith) worker.post { capture.fail(message) }
                }
            } finally {
                timeout.cancel()
                if (receiverProbe === probe) {
                    receiverProbe = null
                    if (streamed && !fromCarWith) {
                        mutableState.update {
                            it.copy(
                                receiverConnection = ReceiverConnection.FAILED,
                                receiverStatus = terminalError ?: "投屏连接已断开",
                            )
                        }
                    }
                    mutableReceiverDiagnostics.value = ReceiverDiagnostics()
                    probedResolution?.let(::prepareCarWithLaunch)
                    if (streamed && fromCarWith && terminalError == null) worker.post { capture.end() }
                }
            }
        }
    }

    private fun stopReceiver() {
        mutableState.update { it.copy(receiverConnection = ReceiverConnection.PENDING) }
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
        projectionScope.launch {
            state.collect { capture ->
                val previous = lastNotificationStatus ?: return@collect
                if (stopping || !capture.active) return@collect
                if (capture.connectionStatus() == previous) return@collect
                // Fast handshake stages can hit Android's notification enqueue rate limit.
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                val latest = state.value
                if (stopping || !latest.active || lastNotificationStatus == null) return@collect
                val status = latest.connectionStatus()
                if (status == lastNotificationStatus) return@collect
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, projectionNotification(status))
                    lastNotificationStatus = status
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to update the projection notification", error)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = entryBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopToken) {
            ProjectionLog.event("用户停止投屏")
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
                    if (stopSelfResult(startId)) {
                        lastNotificationStatus = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == diagnosticToken) {
            if (!stopping && !mutableState.value.active) {
                clearLaunchTicket(this)
                ProjectionLog.event("用户开始投屏")
                mutableState.value = CaptureStatus(phase = CapturePhase.WAITING_FOR_RECEIVER)
                startProjectionForeground()
                val capture = CaptureSession()
                worker.post { session = capture }
                val manualMode = ProjectionPreferences.load(this).manualDisplayMode
                if (manualMode != null) {
                    ProjectionLog.event("手动分辨率和帧率，跳过车机预连接")
                    Log.i(TAG, "Using a manual display mode; starting CarWith before the receiver connection")
                    prepareCarWithLaunch(manualMode)
                } else {
                    startReceiver(capture)
                }
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
        val connectionType = intent?.getIntExtra("usb_type", -1) ?: -1
        if (carWithRequest && connectionType == 0 && !phase.isActive && !stopping &&
            Build.VERSION.SDK_INT >= 33 && ConnectionMode.load(this) == ConnectionMode.CAR_WITH
        ) {
            ProjectionLog.event("CarWith 发起车辆热点连接")
            mutableState.value = CaptureStatus(phase = CapturePhase.WAITING_FOR_RECEIVER, connectionType = connectionType)
            startProjectionForeground()
            val capture = CaptureSession(videoBitrateKbps = ProjectionPreferences.load(this).videoBitrateKbps)
            worker.post { session = capture }
            startReceiver(capture, fromCarWith = true)
            return START_NOT_STICKY
        }
        val resolution = if (carWithRequest && !stopping && (phase == CapturePhase.ARMED || !phase.isActive)) {
            consumeLaunchTicket(this)
        } else null
        if (intent != null && resolution != null) {
            startProjectionForeground()
            ProjectionLog.event("CarWith 启动组件 · usb_type=$connectionType")
            Log.i(TAG, "CarLife start received, usb_type=$connectionType")
            beginCapture(connectionType, resolution)
        } else if (!mutableState.value.active) {
            // Even a declined external start must satisfy startForegroundService's contract.
            startProjectionForeground()
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun prepareCarWithLaunch(resolution: CarPlayResolution) {
        ProjectionLog.event("投屏参数已就绪 · ${resolution.width}×${resolution.height} / ${resolution.frameRate} fps")
        launchResolution = resolution
        main.postDelayed(armTimeout, CAR_WITH_START_TIMEOUT_MS)
        mutableState.update {
            it.copy(
                phase = CapturePhase.READY_TO_START_CAR_WITH,
                receiverStatus = "投屏参数已就绪，正在准备启动 CarWith",
            )
        }
    }

    private fun beginCapture(connectionType: Int, resolution: CarPlayResolution) {
        val preferences = ProjectionPreferences.load(this)
        val encoderResolution = preferences.applyTo(resolution)
        val capture = CaptureSession(videoBitrateKbps = preferences.videoBitrateKbps)
        main.removeCallbacks(armTimeout)
        mutableState.update { it.copy(connectionType = connectionType) }
        bindCapture(capture, encoderResolution)
        startReceiver(capture, expectedResolution = encoderResolution)
    }

    private fun bindCapture(capture: CaptureSession, encoderResolution: CarPlayResolution) {
        mutableState.update {
            it.copy(
                phase = CapturePhase.BINDING,
                targetVideoBitrateKbps = capture.videoBitrateKbps,
                encoderFpsLimit = encoderResolution.frameRate,
                receiverStatus = "正在准备 CarWith 画面",
            )
        }
        worker.post {
            session = capture
            capture.bind(encoderResolution)
        }
    }

    private fun failCarWithStartup(message: String) {
        ProjectionLog.event("CarWith 接管失败")
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

    private fun startProjectionForeground() {
        val status = state.value.connectionStatus()
        startForeground(NOTIFICATION_ID, projectionNotification(status))
        lastNotificationStatus = status
    }

    @Suppress("DEPRECATION")
    private fun projectionNotification(status: ProjectionConnectionStatus): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CarWith 投屏诊断", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openCarNat = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val completed = listOf(status.carWithLink, status.receiverLink)
            .count { it == ProjectionLinkStatus.CONNECTED }
        val progress = completed * 50
        val title = when (status.receiverLink) {
            ProjectionLinkStatus.CONNECTED -> "已连接"
            ProjectionLinkStatus.CONNECTING -> "连接中"
            ProjectionLinkStatus.FAILED -> "连接失败"
            ProjectionLinkStatus.PENDING -> "等待连接"
        }
        fun baseBuilder(): Notification.Builder {
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
                else Notification.Builder(this)
            return builder.setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(title)
                .setContentText(status.message)
                .setContentIntent(openCarNat)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                val iconColor = getColor(
                    if (darkMode) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_800,
                )
                val accent = getColor(
                    if (darkMode) android.R.color.system_accent1_200 else android.R.color.system_accent1_700,
                )
                val connecting = getColor(android.R.color.system_accent1_400)
                val inactive = getColor(
                    if (darkMode) android.R.color.system_neutral1_700 else android.R.color.system_neutral1_300,
                )
                fun linkColor(link: ProjectionLinkStatus) = when (link) {
                    ProjectionLinkStatus.CONNECTED -> accent
                    ProjectionLinkStatus.CONNECTING -> connecting
                    ProjectionLinkStatus.PENDING, ProjectionLinkStatus.FAILED -> inactive
                }

                fun progressIcon(resource: Int) = Icon.createWithResource(this, resource).setTint(iconColor)

                // Some Android 16 builds lack the builder method but accept the notification extra.
                return baseBuilder()
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                    .setStyle(
                        Notification.ProgressStyle()
                            .addProgressSegment(Notification.ProgressStyle.Segment(50).setColor(linkColor(status.carWithLink)))
                            .addProgressSegment(Notification.ProgressStyle.Segment(50).setColor(linkColor(status.receiverLink)))
                            .addProgressPoint(Notification.ProgressStyle.Point(50).setColor(accent))
                            .setStyledByProgress(false)
                            .setProgress(progress)
                            .setProgressStartIcon(progressIcon(R.drawable.ic_mobile_cast))
                            .setProgressEndIcon(progressIcon(R.drawable.ic_car_gear)),
                    )
                    .build()
            } catch (error: LinkageError) {
                Log.w(TAG, "Live update notification API is unavailable", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to build a live update notification", error)
            }
        }
        return baseBuilder().setProgress(100, progress, false).build()
    }

    private inner class CaptureSession(
        val videoBitrateKbps: Int = ProjectionPreferences.DEFAULT_BITRATE_KBPS,
    ) : ServiceConnection {
        private var resolution: CarPlayResolution? = null
        private var manager: ICarLifeCastManager? = null
        private var codec: MediaCodec? = null
        private var surface: Surface? = null
        private var bound = false
        private var closed = false
        private val microphoneSink: (ByteArray) -> Boolean = { pcm ->
            val listener = audioListener
            if (listener == null) false else try {
                // These Binder fields are sample rate, channel count and Android PCM encoding.
                listener.onReceiveAudioStream(pcm, 16_000, 1, 2)
                true
            } catch (_: RemoteException) {
                false
            }
        }

        @Volatile
        private var audioListener: IRemoteAudioListener? = null
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
        private var lastSyncRequest = -1_000L
        private val syncRequestPending = java.util.concurrent.atomic.AtomicBoolean()
        private val sendSyncRequest = Runnable {
            try {
                val now = SystemClock.uptimeMillis()
                if (!closed && now - lastSyncRequest >= 250) {
                    lastSyncRequest = now
                    try {
                        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } catch (error: IllegalStateException) {
                        Log.w(TAG, "Encoder sync request failed", error)
                    }
                }
            } finally {
                syncRequestPending.set(false)
            }
        }
        private val requestSyncFrame: () -> Unit = {
            if (syncRequestPending.compareAndSet(false, true) && !worker.post(sendSyncRequest)) {
                syncRequestPending.set(false)
            }
        }
        private var touchListener: IRemoteTouchListener? = null
        private var touchDownTime: Long? = null
        private var keyListener: IRemoteKeyListener? = null
        private val contacts = mutableListOf<RemoteContact>()
        private val pendingInputs = ArrayDeque<() -> Unit>()
        private var acceptingInput = true
        private var inputDrainScheduled = false
        private var remoteFingersDown = false
        private var waitForTouchRelease = false
        private val cancelTouch: () -> Unit = { sendTouch(emptyList(), cancel = true) }
        private val drainInput = object : Runnable {
            override fun run() {
                val input = synchronized(pendingInputs) { pendingInputs.removeFirstOrNull() }
                try {
                    if (!closed) input?.invoke()
                } finally {
                    synchronized(pendingInputs) {
                        inputDrainScheduled = pendingInputs.isNotEmpty() && worker.post(this)
                        if (!inputDrainScheduled) pendingInputs.clear()
                    }
                }
            }
        }

        private fun enqueueInput(cancel: Boolean = false, input: () -> Unit) {
            synchronized(pendingInputs) {
                if (!acceptingInput) return
                if (cancel || pendingInputs.size >= MAX_PENDING_INPUTS) {
                    // Drop stale input and release any fingers already delivered to CarWith.
                    pendingInputs.clear()
                    pendingInputs.addLast(cancelTouch)
                    waitForTouchRelease = !cancel && remoteFingersDown
                } else {
                    pendingInputs.addLast(input)
                }
                if (!inputDrainScheduled) {
                    inputDrainScheduled = worker.post(drainInput)
                    if (!inputDrainScheduled) pendingInputs.clear()
                }
            }
        }

        private val touchSink: (List<RemoteContact>, Boolean) -> Unit = { report, cancel ->
            synchronized(pendingInputs) {
                remoteFingersDown = !cancel && report.any { it.down }
                if (cancel || !waitForTouchRelease) {
                    enqueueInput(cancel) { sendTouch(report, cancel = false) }
                } else if (!remoteFingersDown) {
                    waitForTouchRelease = false
                }
            }
        }
        private val keySink: (Int) -> Unit = { code ->
            enqueueInput {
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
            ProjectionLog.event(message)
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
            override fun addAudioListener(listener: IRemoteAudioListener?) {
                if (listener == null) return
                worker.post {
                    if (!closed) {
                        audioListener = listener
                        remoteMicrophoneSink = microphoneSink
                    }
                }
            }

            override fun removeAudioListener(listener: IRemoteAudioListener?) {
                worker.post {
                    if (listener?.asBinder() == audioListener?.asBinder()) {
                        audioListener = null
                        if (remoteMicrophoneSink === microphoneSink) remoteMicrophoneSink = null
                    }
                }
            }
        }

        fun bind(size: CarPlayResolution) {
            if (closed) return
            resolution = size
            try {
                bound = bindService(Intent().setComponent(CAR_WITH_SERVICE), this, BIND_AUTO_CREATE)
                if (bound) worker.postDelayed(timeout, 12_000)
                else {
                    ProjectionLog.event("CarWith bindService 返回 false")
                    fail("CarWith cast service is unavailable")
                }
            } catch (error: SecurityException) {
                ProjectionLog.failure("CarWith 拒绝绑定投屏服务", error)
                fail(error.message ?: "CarWith denied the service binding")
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post {
                if (closed) return@post
                manager = ICarLifeCastManager.Stub.asInterface(binder)
                worker.removeCallbacks(timeout)
                ProjectionLog.event("已绑定 CarWith 投屏服务")
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
                ProjectionLog.event("正在调用 CarWith 投屏接口")
                remote.setCastCallback(callback)
                remote.setSystemAudioCallback(audioCallback)
                remote.prepareCast(CastConfig(size.width, size.height, DPI, inputSurface))
                ProjectionLog.event("CarWith prepareCast 已返回，等待编码帧")
                Log.i(TAG, "CarWith prepareCast: ${size.width}×${size.height}")
            } catch (error: Exception) {
                ProjectionLog.failure("CarWith 投屏初始化失败", error)
                Log.w(TAG, "CarWith setup failed for ${size.width}×${size.height}", error)
                fail("无法以 ${size.width}×${size.height} 启动 CarWith 投屏")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith disconnected", "onServiceDisconnected") }
        }

        override fun onBindingDied(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith binding died", "onBindingDied") }
        }

        override fun onNullBinding(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith returned no cast interface", "onNullBinding") }
        }

        private fun startEncoder(size: CarPlayResolution): Surface {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrateKbps * 1_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, size.frameRate)
            if (Build.VERSION.SDK_INT >= 29) {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, size.frameRate.toFloat())
            }
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            ProjectionLog.event("请求 AVC 编码器 · ${size.width}×${size.height}/${size.frameRate} fps · ${videoBitrateKbps} kbps")
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (closed) return
                    val width = format.visibleDimension(MediaFormat.KEY_WIDTH, MediaFormat.KEY_CROP_LEFT, MediaFormat.KEY_CROP_RIGHT)
                    val height = format.visibleDimension(MediaFormat.KEY_HEIGHT, MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_BOTTOM)
                    mutableState.update { it.copy(outputWidth = width, outputHeight = height) }
                    val sps = format.getByteBuffer("csd-0")
                    ProjectionLog.event("AVC 输出格式 · ${width}×${height} · hasSps=${sps != null}")
                    if (sps == null) return
                    readConfiguration(sps, format.getByteBuffer("csd-1"))
                }

                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    if (!closed) {
                        ProjectionLog.failure("视频编码器异常 · code=${error.errorCode} transient=${error.isTransient} recoverable=${error.isRecoverable}", error)
                        fail(error.diagnosticInfo)
                    }
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
                        ProjectionLog.failure("读取或释放 AVC 输出缓冲失败", error)
                        fail(error.message ?: "Could not release encoder output")
                        return
                    }
                    if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                    frames++
                    bytes += info.size
                    if (frames == 1L) {
                        Log.i(TAG, "First H.264 frame, ${info.size} bytes")
                        worker.removeCallbacks(timeout)
                        ProjectionLog.event("CarWith 首个编码帧已输出")
                        mutableState.update { it.copy(phase = CapturePhase.RECEIVING, frames = frames, bytes = bytes) }
                    }
                }
            }, worker)
            ProjectionLog.event("配置 AVC 编码器 · ${encoder.name}")
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
            if (info.offset < 0 || info.offset > data.capacity() || info.size > data.capacity() - info.offset) {
                Log.w(TAG, "Invalid AVC output buffer range")
                return
            }
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

        fun fail(message: String, reason: String? = null) {
            if (closed) return
            val current = mutableState.value
            ProjectionLog.event("投屏失败 · phase=${current.phase} displayReady=${current.displayId != null} encodedFrames=$frames" +
                (reason?.let { " · $it" } ?: ""))
            Log.w(TAG, message)
            mutableState.update { it.copy(phase = CapturePhase.FAILED, error = message) }
            finish()
            stopSelf()
        }

        fun end() {
            if (closed) return
            finish()
            stopSelf()
        }

        fun finish() {
            if (closed) return
            closed = true
            if (remoteTouchSink === touchSink) remoteTouchSink = null
            if (keyFrameSink === requestSyncFrame) keyFrameSink = null
            synchronized(pendingInputs) {
                acceptingInput = false
                pendingInputs.clear()
                worker.removeCallbacks(drainInput)
            }
            worker.removeCallbacks(sendSyncRequest)
            touchListener = null
            if (remoteKeySink === keySink) remoteKeySink = null
            keyListener = null
            worker.removeCallbacks(timeout)
            worker.removeCallbacks(sampleOutput)
            audioListener = null
            if (remoteMicrophoneSink === microphoneSink) remoteMicrophoneSink = null
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
        private const val MAX_PENDING_INPUTS = 32
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
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
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

        @Volatile
        private var remoteMicrophoneSink: ((ByteArray) -> Boolean)? = null

        internal fun remoteMicrophone(pcm: ByteArray): Boolean = remoteMicrophoneSink?.invoke(pcm) ?: false

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

enum class ReceiverConnection {
    PENDING, CONNECTED, FAILED
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
    val receiverConnection: ReceiverConnection = ReceiverConnection.PENDING,
    val receiverDisplayChanged: Boolean = false,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val outputFps: Double? = null,
    val outputMbps: Double? = null,
    val targetVideoBitrateKbps: Int? = null,
    val encoderFpsLimit: Int? = null,
) {
    val active: Boolean
        get() = phase.isActive
}
