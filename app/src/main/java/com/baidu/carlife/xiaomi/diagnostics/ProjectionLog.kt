package com.baidu.carlife.xiaomi.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object ProjectionLog {
    private const val TAG = "CarNatLog"
    private val exportLock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)
    private val rtspCode = Regex("RTSP[^0-9]{0,12}([1-5][0-9]{2})")
    private lateinit var files: RollingLogFiles

    fun initialize(context: Context) {
        files = RollingLogFiles(File(context.noBackupFilesDir, "projection-logs"))
        val prefs = context.getSharedPreferences("log_process", Context.MODE_PRIVATE)
        val previousPid = prefs.getInt("pid", 0)
        val previousStart = prefs.getLong("started", 0)
        val expectedRestartAt = prefs.getLong("carwith_restart_at", 0)
        prefs.edit().putInt("pid", Process.myPid()).putLong("started", System.currentTimeMillis())
            .remove("carwith_restart_at").commit()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                failure("未捕获异常", error)
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (Build.VERSION.SDK_INT >= 30 && previousPid != 0) {
                runCatching { collectExit(context, previousPid, previousStart, expectedRestartAt) }
                    .onFailure { Log.w(TAG, "Could not collect process exit", it) }
            }
        }
    }

    fun expectCarWithRestart(context: Context) {
        event("请求启动 CarWith，组件可能被系统强停后重新拉起")
        context.getSharedPreferences("log_process", Context.MODE_PRIVATE)
            .edit().putLong("carwith_restart_at", System.currentTimeMillis()).commit()
    }

    fun stage(message: String) {
        val entry = when {
            message.startsWith("正在等待配对设备") -> "正在等待已配对的车机蓝牙连接"
            message.startsWith("已收到 CarPlay 网络端点") -> "已收到 CarPlay 网络端点"
            message.startsWith("已收到 iAP2 接收端身份") -> "已收到 iAP2 接收端身份"
            message.startsWith("主屏视频 TCP 已连接") -> "主屏视频 TCP 已连接，RECORD 已确认"
            else -> message
        }
        event(entry)
    }

    fun failure(stage: String, error: Throwable) {
        val status = rtspCode.find(error.message.orEmpty())?.groupValues?.get(1)
        event(buildString {
            append(stage).append(" · ").append(error.javaClass.simpleName)
            if (status != null) append(" · RTSP ").append(status)
            error.stackTrace.take(10).forEach { append("\n  at ").append(it) }
            error.cause?.takeUnless { it === error }?.let {
                append("\n  caused by ").append(it.javaClass.simpleName)
                it.stackTrace.take(5).forEach { frame -> append("\n  at ").append(frame) }
            }
        })
    }

    @Synchronized
    fun event(message: String) {
        if (!::files.isInitialized) return
        runCatching {
            files.append("${timestamp.format(Date())} ${message.take(2_000)}\n")
        }.onFailure { Log.w(TAG, "Could not write projection log", it) }
    }

    @Synchronized
    fun snapshot(): String = if (::files.isInitialized) files.snapshot() else ""

    @RequiresApi(30)
    private fun collectExit(context: Context, pid: Int, started: Long, expectedRestartAt: Long) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, pid, 5)
            .firstOrNull { it.timestamp >= started } ?: return
        if (exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            exit.timestamp - expectedRestartAt in 0L..10_000L
        ) {
            event("CarWith 启动后的预期组件重启")
            return
        }
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "应用崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
            ApplicationExitInfo.REASON_ANR -> "应用无响应"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
            ApplicationExitInfo.REASON_SIGNALED -> "进程被信号终止"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "进程初始化失败"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过多"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程终止"
            ApplicationExitInfo.REASON_UNKNOWN -> "原因未知"
            else -> return
        }
        event("上次进程异常退出 · $reason · 系统原因码 ${exit.reason} · 状态 ${exit.status}")
        export(context, automatic = true)
    }

    @RequiresApi(29)
    fun export(context: Context, automatic: Boolean = false): Uri = synchronized(exportLock) {
        val prefs = context.getSharedPreferences("log_exports", Context.MODE_PRIVATE)
        val key = if (automatic) "last_exit" else "latest"
        val name = if (automatic) "CarNat-last-exit.txt" else "CarNat-log.txt"
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/CarNat/"
        val old = prefs.getString(key, null)?.let(Uri::parse)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建日志文件")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入日志文件")
            output.bufferedWriter().use {
                it.write("CarNat · Android API ${Build.VERSION.SDK_INT}\n")
                it.write(snapshot())
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        if (!prefs.edit().putString(key, uri.toString()).commit()) {
            runCatching { resolver.delete(uri, null, null) }
            throw IOException("无法记住导出的日志文件")
        }
        if (old != null && old != uri) runCatching {
            val stillInLogFolder = resolver.query(
                old, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
            )?.use { cursor -> cursor.moveToFirst() && cursor.getString(0) == relativePath } ?: false
            if (stillInLogFolder) resolver.delete(old, null, null)
        }
        // MediaStore may add a numeric suffix if the previous file existed during insertion.
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
        }
        uri
    }
}
