package com.baidu.carlife.service

import android.app.Service
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
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CarlifeConnectService : Service() {
    private val entryBinder = Binder()
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private var session: CaptureSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val armTimeout = Runnable {
        if (mutableState.value.phase == CapturePhase.ARMED) {
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
        if (intent?.action == diagnosticToken) {
            if (!mutableState.value.active) {
                startForeground(NOTIFICATION_ID, diagnosticNotification())
                mutableState.value = CaptureStatus(phase = CapturePhase.ARMED)
                Log.i(TAG, "Armed for CarWith's CarLife start")
                main.postDelayed(armTimeout, 90_000)
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
        if (intent != null && carWithRequest && mutableState.value.phase == CapturePhase.ARMED) {
            val connectionType = intent.getIntExtra("usb_type", -1)
            Log.i(TAG, "CarLife start received, usb_type=$connectionType")
            beginCapture(connectionType)
        } else if (!mutableState.value.active) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(connectionType: Int?) {
        main.removeCallbacks(armTimeout)
        mutableState.update { it.copy(phase = CapturePhase.BINDING, connectionType = connectionType) }
        worker.post {
            CaptureSession().also {
                session = it
                it.bind()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(armTimeout)
        mutableState.update {
            if (it.phase == CapturePhase.ARMED) it.copy(phase = CapturePhase.STOPPED) else it
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
        private val timeout = Runnable {
            val message = when {
                manager == null -> "Timed out binding the CarWith cast service"
                mutableState.value.displayId == null -> "CarWith did not return onCastPrepared"
                else -> "CarWith prepared a display but no encoded frame arrived"
            }
            fail(message)
        }

        private val audioCallback = object : ISystemAudioCallback.Stub() {
            override fun onAudioStreamReady(data: ByteArray?, offset: Int, length: Int, sampleRate: Int, channels: Int) = Unit
            override fun onAudioStateChange(stream: Int, state: Int) = Unit
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
            override fun addTouchListener(listener: IRemoteTouchListener?) = Unit
            override fun removeTouchListener(listener: IRemoteTouchListener?) = Unit
            override fun addKeyListener(listener: IRemoteKeyListener?) = Unit
            override fun removeKeyListener(listener: IRemoteKeyListener?) = Unit
            override fun sendMusicInfo(info: MusicInfo?) = Unit
            override fun notifyMusicProgress(positionMs: Long) = Unit
            override fun invokeMethod(intent: Intent?): Bundle = Bundle()
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
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    if (!closed) fail(error.diagnosticInfo)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (closed) return
                    try {
                        codec.releaseOutputBuffer(index, false)
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
            return inputSurface
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
        private const val TAG = "CarNatCapture"
        private const val CHANNEL_ID = "carwith_diagnostics"
        private const val NOTIFICATION_ID = 1
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val DPI = 160
        private val mutableState = MutableStateFlow(CaptureStatus())
        val state = mutableState.asStateFlow()

        fun startDiagnostic(context: Context) {
            val intent = Intent(context, CarlifeConnectService::class.java).setAction(diagnosticToken)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopDiagnostic(context: Context) {
            context.stopService(Intent(context, CarlifeConnectService::class.java))
        }

        fun markSetupReady() {
            mutableState.update { it.copy(setupReady = true) }
        }

        fun captureAfterSetup(context: Context) {
            context.startService(Intent(context, CarlifeConnectService::class.java).setAction(captureToken))
        }
    }
}

enum class CapturePhase { IDLE, ARMED, BINDING, WAITING_FOR_FRAMES, RECEIVING, STOPPED, FAILED }

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val setupReady: Boolean = false,
    val connectionType: Int? = null,
    val displayId: Int? = null,
    val frames: Long = 0,
    val bytes: Long = 0,
    val error: String? = null,
) {
    val active: Boolean
        get() = when (phase) {
            CapturePhase.ARMED, CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES, CapturePhase.RECEIVING -> true
            else -> false
        }
}
