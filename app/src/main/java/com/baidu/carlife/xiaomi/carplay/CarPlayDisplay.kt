package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.net.ProtocolException

internal data class CarPlayResolution(val width: Int, val height: Int)

internal data class CarPlayDisplay(val uuid: String, val resolution: CarPlayResolution) {
    companion object {
        const val MAIN_SCREEN = 110

        fun fromReceiverInfo(info: NSDictionary): CarPlayDisplay {
            val displays = (info.objectForKey("displays") as? NSArray)?.array.orEmpty()
                .filterIsInstance<NSDictionary>()
            val display = displays.firstOrNull { it.type == MAIN_SCREEN.toLong() }
                ?: displays.firstOrNull { it.type == null }
                ?: throw ProtocolException("CarPlay receiver did not advertise a main display")
            val uuid = (display.objectForKey("uuid") as? NSString)?.content?.takeIf { it.isNotBlank() }
                ?: throw ProtocolException("CarPlay main display has no UUID")
            return CarPlayDisplay(uuid, CarPlayResolution(display.pixels("widthPixels"), display.pixels("heightPixels")))
        }

        private val NSDictionary.type: Long?
            get() = (objectForKey("type") as? NSNumber)?.longValue()

        private fun NSDictionary.pixels(key: String): Int {
            return (objectForKey(key) as? NSNumber)?.longValue()
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw ProtocolException("Invalid CarPlay main display dimension: $key")
        }
    }
}
