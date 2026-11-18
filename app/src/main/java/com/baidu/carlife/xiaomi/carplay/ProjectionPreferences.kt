package com.baidu.carlife.xiaomi.carplay

import android.content.Context

internal enum class AudioPreference(val label: String) {
    AUTO("自动"), AAC("优先 AAC"), PCM("优先 PCM")
}

internal data class ProjectionPreferences(
    val width: Int? = null,
    val height: Int? = null,
    val fpsLimit: Int? = null,
    val videoBitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    val audioPreference: AudioPreference = AudioPreference.AUTO,
    val mediaBufferMs: Int? = null,
    val navigationBufferMs: Int? = null,
    val useCarMicrophone: Boolean = false,
) {
    val manualDisplayMode: CarPlayResolution?
        get() {
            val configuredWidth = width ?: return null
            val configuredHeight = height ?: return null
            val configuredFps = fpsLimit ?: return null
            return CarPlayResolution(configuredWidth, configuredHeight, configuredFps)
        }

    fun applyTo(receiver: CarPlayResolution): CarPlayResolution = CarPlayResolution(
        width ?: receiver.width,
        height ?: receiver.height,
        fpsLimit?.let { minOf(it, receiver.maxFps ?: it) } ?: receiver.maxFps,
    )

    fun save(context: Context): Boolean = store(context).edit()
        .putInt("width", width ?: 0)
        .putInt("height", height ?: 0)
        .putInt("fps_limit", fpsLimit ?: 0)
        .putInt("video_bitrate", videoBitrateKbps)
        .putString("audio_preference", audioPreference.name)
        .putInt("media_buffer", mediaBufferMs ?: 0)
        .putInt("navigation_buffer", navigationBufferMs ?: 0)
        .putBoolean("use_car_microphone", useCarMicrophone)
        .commit()

    companion object {
        const val DEFAULT_BITRATE_KBPS = 4_000
        val dimensions = 320..4096
        val frameRates = 10..120
        val videoBitrates = 1_000..20_000
        val audioBuffers = 40..1_000

        private fun store(context: Context) =
            context.getSharedPreferences("projection_preferences", Context.MODE_PRIVATE)

        fun load(context: Context): ProjectionPreferences = store(context).let { prefs ->
            fun read(key: String, range: IntRange) = prefs.getInt(key, 0).takeIf { it in range }
            val width = read("width", dimensions)?.takeIf { it % 2 == 0 }
            val height = read("height", dimensions)?.takeIf { it % 2 == 0 }
            val audioPreference = AudioPreference.entries.firstOrNull {
                it.name == prefs.getString("audio_preference", null)
            }
            ProjectionPreferences(
                width = width.takeIf { height != null },
                height = height.takeIf { width != null },
                fpsLimit = read("fps_limit", frameRates),
                videoBitrateKbps = read("video_bitrate", videoBitrates) ?: DEFAULT_BITRATE_KBPS,
                audioPreference = audioPreference ?: AudioPreference.AUTO,
                mediaBufferMs = read("media_buffer", audioBuffers),
                navigationBufferMs = read("navigation_buffer", audioBuffers),
                useCarMicrophone = prefs.getBoolean("use_car_microphone", false),
            )
        }
    }
}
