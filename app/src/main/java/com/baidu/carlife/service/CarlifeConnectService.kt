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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
import com.baidu.carlife.xiaomi.carplay.AvcPayload
import com.baidu.carlife.xiaomi.carplay.CarPlayMediaAudio
import com.baidu.carlife.xiaomi.carplay.CarWithVideoFeed
import java.nio.ByteBuffer
import java.util.UUID
import com.baidu.carlife.xiaomi.carplay.BluetoothReceiverProbe
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
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

    @android.annotation.SuppressLint("MissingPermission")
    private fun startReceiver() {
        val settings = ReceiverSettings.load(this)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (!settings.valid || adapter == null || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            mutableState.update { it.copy(receiverStatus = "请检查接收端设置和附近设备权限") }
            return
        }
        val probe = BluetoothReceiverProbe()
        receiverProbe = probe
        val timeout = projectionScope.launch {
            delay(45_000)
            mutableState.update { it.copy(receiverStatus = "等待接收端超时") }
            stopReceiver()
        }
        receiverJob = projectionScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    probe.run(adapter, settings.name, settings.networkPairingCode, settings.address, { timeout.cancel() }) { stage ->
                        Log.i("CarNatIap2", stage)
                        projectionScope.launch {
                            if (receiverProbe === probe) mutableState.update { it.copy(receiverStatus = stage) }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (receiverProbe === probe) {
                    Log.i("CarNatIap2", "Receiver session ended", failure)
                    mutableState.update { it.copy(receiverStatus = failure.message ?: "投屏连接失败") }
                }
            } finally {
                timeout.cancel()
                if (receiverProbe === probe) receiverProbe = null
            }
        }
    }

    private fun stopReceiver() {
        receiverProbe?.close()
        receiverProbe = null
        receiverJob?.cancel()
        receiverJob = null
    }
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private var session: CaptureSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val armTimeout = Runnable {
        if (mutableState.value.phase == CapturePhase.ARMED) {
            clearArm(this)
            val message = "CarWith did not start its CarLife component"
            Log.w(TAG, message)
            mutableState.value = CaptureStatus(phase = CapturePhase.FAILED, error = message)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("CarWith capture").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent): IBinder = entryBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopToken) {
            stopReceiver()
            main.removeCallbacks(armTimeout)
            clearArm(this)
            mutableState.update {
                if (it.phase == CapturePhase.ARMED) it.copy(phase = CapturePhase.STOPPED, receiverStatus = null) else it
            }
            worker.post {
                session?.finish()
                session = null
                mutableState.update { it.copy(phase = CapturePhase.STOPPED, receiverStatus = null) }
                main.post {
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == diagnosticToken) {
            if (!mutableState.value.active) {
                startForeground(NOTIFICATION_ID, diagnosticNotification())
                recordArm(this)
                mutableState.value = CaptureStatus(phase = CapturePhase.ARMED)
                Log.i(TAG, "Armed for CarWith's CarLife start")
                main.postDelayed(armTimeout, ARM_WINDOW_MS)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == captureToken) {
            if (mutableState.value.phase == CapturePhase.ARMED && mutableState.value.setupReady) {
                Log.i(TAG, "User requested capture after CarWith setup")
                beginCapture(null)
            }
            return START_NOT_STICKY
        }

        // The exported service accepts only the fixed CarWith endpoint, during a local opt-in.
        val carWithRequest = intent != null &&
            intent.getStringExtra("bindPackageName") == CAR_WITH_SERVICE.packageName &&
            intent.getStringExtra("bindClassName") == CAR_WITH_SERVICE.className
        val phase = mutableState.value.phase
        if (intent != null && carWithRequest &&
            (phase == CapturePhase.ARMED || (!phase.isActive && hasValidArm(this)))
        ) {
            startForeground(NOTIFICATION_ID, diagnosticNotification())
            val connectionType = intent.getIntExtra("usb_type", -1)
            Log.i(TAG, "CarLife start received, usb_type=$connectionType")
            beginCapture(connectionType)
        } else if (!mutableState.value.active) {
            // Even a declined external start must satisfy startForegroundService's contract.
            startForeground(NOTIFICATION_ID, diagnosticNotification())
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(connectionType: Int?) {
        clearArm(this)
        main.removeCallbacks(armTimeout)
        mutableState.update { it.copy(phase = CapturePhase.BINDING, connectionType = connectionType) }
        startReceiver()
        worker.post {
            CaptureSession().also {
                session = it
                it.bind()
            }
        }
    }

    override fun onDestroy() {
        stopReceiver()
        projectionScope.cancel()
        main.removeCallbacks(armTimeout)
        mutableState.update {
            if (it.phase == CapturePhase.ARMED) it.copy(phase = CapturePhase.STOPPED, receiverStatus = null) else it
        }
        worker.post {
            session?.finish()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun diagnosticNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
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
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private inner class CaptureSession : ServiceConnection {
        private var manager: ICarLifeCastManager? = null
        private var codec: MediaCodec? = null
        private var surface: Surface? = null
        private var bound = false
        private var closed = false
        private var frames = 0L
        private var bytes = 0L
        private var publishedAtMs = 0L
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
            if (closed || mutableState.value.displayId == null) return
            val now = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                val properties = contacts.map {
                    MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }.toTypedArray()
                val coordinates = contacts.map {
                    MotionEvent.PointerCoords().apply {
                        x = it.x * (WIDTH - 1)
                        y = it.y * (HEIGHT - 1)
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
                try {
                    val remote = ICarLifeCastManager.Stub.asInterface(binder)
                    manager = remote
                    Log.i(TAG, "Bound to CarWith")
                    val inputSurface = startEncoder()
                    mutableState.update { it.copy(phase = CapturePhase.WAITING_FOR_FRAMES) }
                    remote.setCastCallback(callback)
                    remote.setSystemAudioCallback(audioCallback)
                    remote.prepareCast(CastConfig(WIDTH, HEIGHT, DPI, inputSurface))
                    Log.i(TAG, "prepareCast returned")
                } catch (error: Exception) {
                    fail(error.message ?: error.javaClass.simpleName)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith disconnected") }
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) {
            worker.post { if (!closed) fail("CarWith returned no cast interface") }
        }

        private fun startEncoder(): Surface {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
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
                    if (frames == 1L) Log.i(TAG, "First H.264 frame, ${info.size} bytes")
                    val now = SystemClock.elapsedRealtime()
                    if (frames == 1L || now - publishedAtMs >= 500) {
                        worker.removeCallbacks(timeout)
                        publishFrames(now)
                    }
                }
            }, worker)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            surface = inputSurface
            encoder.start()
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
            CarWithVideoFeed.offer(data.slice(), configuration, WIDTH, HEIGHT, keyFrame, info.presentationTimeUs)
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

        private fun publishFrames(now: Long) {
            publishedAtMs = now
            mutableState.update { it.copy(phase = CapturePhase.RECEIVING, frames = frames, bytes = bytes) }
        }

        private fun fail(message: String) {
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
            try {
                manager?.stopCast()
            } catch (_: RemoteException) {
                // CarWith may already have exited.
            } finally {
                if (bound) unbindService(this)
                runCatching { codec?.release() }
                surface?.release()
                mutableState.update {
                    if (it.phase == CapturePhase.FAILED) it else
                        it.copy(phase = CapturePhase.STOPPED, frames = frames, bytes = bytes)
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
        private val captureToken = UUID.randomUUID().toString()
        private val stopToken = UUID.randomUUID().toString()
        private const val TAG = "CarNatCapture"
        private const val CHANNEL_ID = "carwith_diagnostics"
        private const val NOTIFICATION_ID = 1
        private const val ARM_WINDOW_MS = 90_000L
        private const val ARM_PREFERENCES = "capture_opt_in"
        private const val ARMED_AT = "elapsed_ms"
        private const val ARMED_AT_WALL = "wall_ms"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val DPI = 160
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

        private fun recordArm(context: Context) {
            context.getSharedPreferences(ARM_PREFERENCES, MODE_PRIVATE).edit()
                .putLong(ARMED_AT, SystemClock.elapsedRealtime())
                .putLong(ARMED_AT_WALL, System.currentTimeMillis())
                .commit()
        }

        private fun hasValidArm(context: Context): Boolean {
            val preferences = context.getSharedPreferences(ARM_PREFERENCES, MODE_PRIVATE)
            val elapsedAge = SystemClock.elapsedRealtime() - preferences.getLong(ARMED_AT, 0)
            val wallAge = System.currentTimeMillis() - preferences.getLong(ARMED_AT_WALL, 0)
            return elapsedAge in 0 until ARM_WINDOW_MS && wallAge in 0 until ARM_WINDOW_MS
        }

        private fun clearArm(context: Context) {
            context.getSharedPreferences(ARM_PREFERENCES, MODE_PRIVATE).edit()
                .remove(ARMED_AT)
                .remove(ARMED_AT_WALL)
                .commit()
        }

        fun markSetupReady() {
            mutableState.update { it.copy(setupReady = true) }
        }

        fun captureAfterSetup(context: Context) {
            context.startService(Intent(context, CarlifeConnectService::class.java).setAction(captureToken))
        }
    }
}

internal data class RemoteContact(val id: Int, val x: Float, val y: Float, val down: Boolean)

enum class CapturePhase(val isActive: Boolean = false) {
    IDLE, ARMED(true), BINDING(true), WAITING_FOR_FRAMES(true), RECEIVING(true), STOPPED, FAILED
}

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val setupReady: Boolean = false,
    val connectionType: Int? = null,
    val displayId: Int? = null,
    val frames: Long = 0,
    val bytes: Long = 0,
    val avcConfigBytes: Int? = null,
    val avcKeyFrameBytes: Int? = null,
    val error: String? = null,
    val receiverStatus: String? = null,
) {
    val active: Boolean
        get() = phase.isActive
}
