package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import java.net.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayPlistTest {
    @Test
    fun acceptsNestedDisplayParameters() {
        val screen = NSDictionary().apply {
            put("name", "车机")
            put("width", 1412)
            put("height", 654)
        }
        val info = NSDictionary().apply {
            put("displays", NSArray(1).apply { setValue(0, screen) })
            put("metadata", NSData(ByteArray(32)))
        }
        val bytes = BinaryPropertyListWriter.writeToArray(info)
        assertEquals(info, CarPlayPlist.readDictionary(bytes))
    }

    @Test
    fun rejectsOversizedResponsesAndNonDictionaryRoots() {
        assertThrows(ProtocolException::class.java) {
            CarPlayPlist.readDictionary(ByteArray(4 * 1024 * 1024 + 1))
        }
        assertThrows(ProtocolException::class.java) {
            CarPlayPlist.readDictionary(BinaryPropertyListWriter.writeToArray(NSArray(0)))
        }
    }
}
