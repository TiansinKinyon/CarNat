package com.baidu.carlife.xiaomi.carplay

import androidx.annotation.DrawableRes
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CaptureStatus
import com.baidu.carlife.service.ReceiverConnection
import com.baidu.carlife.xiaomi.R

internal enum class ProjectionLinkStatus(@get:DrawableRes val icon: Int, val description: String) {
    PENDING(R.drawable.ic_more_horiz, "未连接"),
    CONNECTING(R.drawable.ic_more_horiz, "正在连接"),
    CONNECTED(R.drawable.ic_check_circle, "已连接"),
    FAILED(R.drawable.ic_error, "连接失败"),
}

internal data class ProjectionConnectionStatus(
    val carWithLink: ProjectionLinkStatus,
    val receiverLink: ProjectionLinkStatus,
    val message: String,
)

internal fun CaptureStatus.connectionStatus(
    supported: Boolean = true,
    localError: String? = null,
): ProjectionConnectionStatus {
    val connectionError = localError ?: error
    val showError = supported && (connectionError != null || phase == CapturePhase.FAILED ||
        receiverConnection == ReceiverConnection.FAILED)
    val showSuccess = supported && !showError && active && receiverConnection == ReceiverConnection.CONNECTED
    val carWithLink = when {
        supported && phase == CapturePhase.RECEIVING -> ProjectionLinkStatus.CONNECTED
        showError -> ProjectionLinkStatus.FAILED
        !supported -> ProjectionLinkStatus.PENDING
        else -> when (phase) {
            CapturePhase.ARMED, CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES ->
                ProjectionLinkStatus.CONNECTING
            else -> ProjectionLinkStatus.PENDING
        }
    }
    val receiverLink = when {
        showError -> ProjectionLinkStatus.FAILED
        showSuccess -> ProjectionLinkStatus.CONNECTED
        supported && active -> ProjectionLinkStatus.CONNECTING
        else -> ProjectionLinkStatus.PENDING
    }
    val message = when {
        !supported -> "CarPlay 投屏需要 Android 13 或更新版本"
        showSuccess -> "连接成功"
        else -> connectionError ?: receiverStatus ?: when (phase) {
            CapturePhase.WAITING_FOR_RECEIVER -> "正在等待车载 CarPlay 连接"
            CapturePhase.READY_TO_START_CAR_WITH -> "车机连接成功，返回 CarNat 即可启动 CarWith"
            CapturePhase.ARMED -> "正在启动 CarWith"
            CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES -> "正在准备 CarWith 画面"
            CapturePhase.RECEIVING -> "正在投屏"
            CapturePhase.STOPPED -> "投屏已停止"
            else -> "等待连接"
        }
    }
    return ProjectionConnectionStatus(carWithLink, receiverLink, message)
}
