package com.baidu.carlife.xiaomi.carplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

internal enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC);

    companion object {
        fun hardwareHevcEncoder(size: CarPlayResolution? = null): String? {
            if (Build.VERSION.SDK_INT < 29) return null
            return runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    if (!info.isEncoder || !info.isHardwareAccelerated || info.isAlias ||
                        HEVC.mime !in info.supportedTypes
                    ) return@firstOrNull false
                    val caps = info.getCapabilitiesForType(HEVC.mime)
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats &&
                        caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain } &&
                        (size == null || caps.videoCapabilities?.areSizeAndRateSupported(
                            size.width, size.height, size.frameRate.toDouble(),
                        ) == true)
                }?.name
            }.getOrNull()
        }
    }
}
