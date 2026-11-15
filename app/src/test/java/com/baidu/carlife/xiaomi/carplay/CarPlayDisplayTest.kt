package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import java.net.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDisplayTest {
    @Test
    fun usesAdvertisedUltrawideSizeOfMainDisplay() {
        val info = info(display("cluster", 1280, 480, 111), display("main", 1920, 766, 110))
        assertEquals(CarPlayDisplay("main", CarPlayResolution(1920, 766)), CarPlayDisplay.fromReceiverInfo(info))
    }

    @Test
    fun acceptsUntypedMainDisplayWithPortraitSize() {
        val info = info(display("main", 720, 1280))
        assertEquals(CarPlayResolution(720, 1280), CarPlayDisplay.fromReceiverInfo(info).resolution)
    }

    @Test
    fun readsUpdatedFrameRateFromMainDisplay() {
        val original = info(display("main", 1920, 766, 110).apply { put("maxFPS", 30) })
        val updated = info(display("main", 1600, 1068, 110).apply { put("maxFPS", 60) })
        assertEquals(CarPlayResolution(1920, 766, 30), CarPlayDisplay.fromReceiverInfo(original).resolution)
        assertEquals(CarPlayResolution(1600, 1068, 60), CarPlayDisplay.fromReceiverInfo(updated).resolution)
    }

    @Test
    fun defaultsToThirtyFpsWhenReceiverOmitsLimit() {
        val info = info(display("main", 1920, 766, 110))
        assertEquals(30, CarPlayDisplay.fromReceiverInfo(info).resolution.frameRate)
    }

    @Test
    fun detectsEncoderSettingsChangesBetweenConnections() {
        val initial = CarPlayResolution(1920, 766)
        assertTrue(initial.matchesEncoderSettings(CarPlayResolution(1920, 766, 30)))
        assertFalse(initial.matchesEncoderSettings(CarPlayResolution(1920, 766, 60)))
        assertFalse(initial.matchesEncoderSettings(CarPlayResolution(1600, 1068, 30)))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsInvalidDisplaySize() {
        CarPlayDisplay.fromReceiverInfo(info(display("main", 1920, 0, 110)))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsClusterOnlyDisplay() {
        CarPlayDisplay.fromReceiverInfo(info(display("cluster", 1280, 480, 111)))
    }

    private fun info(vararg displays: NSDictionary) = NSDictionary().apply {
        put("displays", NSArray(displays.size).apply {
            displays.forEachIndexed { index, display -> setValue(index, display) }
        })
    }

    private fun display(uuid: String, width: Int, height: Int, type: Int? = null) = NSDictionary().apply {
        put("uuid", uuid)
        put("widthPixels", width)
        put("heightPixels", height)
        type?.let { put("type", it) }
    }
}
