package com.baidu.carlife.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.view.Surface
import com.baidu.carlife.mixing.CastConfig
import com.baidu.carlife.mixing.MusicInfo
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback
import com.baidu.carlife.mixing.aidl.ICarLifeCastManager
import com.baidu.carlife.mixing.aidl.IRemoteAudioListener
import com.baidu.carlife.mixing.aidl.IRemoteKeyListener
import com.baidu.carlife.mixing.aidl.IRemoteTouchListener
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CarlifeConnectService : Service() {
    private val entryBinder = Binder()
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private var session: CaptureSession? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("CarWith capture").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent): IBinder = entryBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != diagnosticToken) {
            if (!mutableState.value.active) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!mutableState.value.active) {
            mutableState.value = CaptureStatus(phase = CapturePhase.BINDING)
            worker.post {
                CaptureSession().also {
                    session = it
                    it.bind()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.post {
            session?.finish()
            thread.quitSafely()
        }
        super.onDestroy()
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
        private val timeout = Runnable { fail("Timed out waiting for a CarWith video frame") }

        private val callback = object : ICarLifeCastCallback.Stub() {
            override fun onCastPrepared(displayId: Int) {
                worker.post {
                    if (!closed) mutableState.update { it.copy(displayId = displayId) }
                }
            }

            override fun onServiceDestroy() {
                worker.post { if (!closed) fail("CarWith ended the cast") }
            }

            override fun onServiceReady() = Unit
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
                    val inputSurface = startEncoder()
                    mutableState.update { it.copy(phase = CapturePhase.WAITING_FOR_FRAMES) }
                    remote.setCastCallback(callback)
                    remote.prepareCast(CastConfig(WIDTH, HEIGHT, DPI, inputSurface))
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
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val DPI = 160
        private val mutableState = MutableStateFlow(CaptureStatus())
        val state = mutableState.asStateFlow()

        fun startDiagnostic(context: Context) {
            context.startService(Intent(context, CarlifeConnectService::class.java).setAction(diagnosticToken))
        }

        fun stopDiagnostic(context: Context) {
            context.stopService(Intent(context, CarlifeConnectService::class.java))
        }
    }
}

enum class CapturePhase { IDLE, BINDING, WAITING_FOR_FRAMES, RECEIVING, STOPPED, FAILED }

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val displayId: Int? = null,
    val frames: Long = 0,
    val bytes: Long = 0,
    val error: String? = null,
) {
    val active: Boolean
        get() = when (phase) {
            CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES, CapturePhase.RECEIVING -> true
            else -> false
        }
}
