package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.net.ProtocolException

internal data class CarPlayResolution(val width: Int, val height: Int, val maxFps: Int? = null) {
    val frameRate: Int get() = maxFps ?: 30

    fun matchesEncoderSettings(other: CarPlayResolution): Boolean =
        width == other.width && height == other.height && frameRate == other.frameRate
}

internal data class CarPlayDisplay(val uuid: String, val resolution: CarPlayResolution) {
    companion object {
        const val MAIN_SCREEN = 110

        fun fromReceiverInfo(info: NSDictionary, protocol: ReceiverProtocol = ReceiverProtocol.GENERIC): CarPlayDisplay {
            val displays = (info.objectForKey("displays") as? NSArray)?.array.orEmpty()
                .filterIsInstance<NSDictionary>()
            val primary = displays.asSequence().filter { it.type == MAIN_SCREEN.toLong() }
            val legacy = displays.asSequence().filter {
                it.objectForKey("type") == null || (protocol == ReceiverProtocol.VOLKSWAGEN && it.type == 0L)
            }
            return (primary + legacy).firstNotNullOfOrNull { parseDisplay(it, protocol) }
                ?: throw ProtocolException("CarPlay receiver did not advertise a valid main display")
        }

        private fun parseDisplay(display: NSDictionary, protocol: ReceiverProtocol): CarPlayDisplay? {
            val value = display.objectForKey("uuid")
            val uuid = when {
                value is NSString -> value.content
                protocol.isVehicle && value is NSData && value.bytes().size == 16 -> {
                    val bytes = java.nio.ByteBuffer.wrap(value.bytes())
                    java.util.UUID(bytes.long, bytes.long).toString().uppercase(java.util.Locale.ROOT)
                }
                else -> null
            }?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) } ?: return null
            val maxFps = (display.objectForKey("maxFPS") as? NSNumber)?.takeIf { it.isInteger }
                ?.longValue()?.takeIf { it > 0 }
                ?.coerceAtMost(ProjectionPreferences.frameRates.last.toLong())?.toInt()
            val width = display.pixels("widthPixels") ?: return null
            val height = display.pixels("heightPixels") ?: return null
            return CarPlayDisplay(uuid, CarPlayResolution(width, height, maxFps))
        }

        private val NSDictionary.type: Long?
            get() = (objectForKey("type") as? NSNumber)?.takeIf { it.isInteger }?.longValue()

        private fun NSDictionary.pixels(key: String): Int? {
            return (objectForKey(key) as? NSNumber)?.takeIf { it.isInteger }?.longValue()
                ?.takeIf { it in 1L..ProjectionPreferences.dimensions.last.toLong() }?.toInt()
        }
    }
}
