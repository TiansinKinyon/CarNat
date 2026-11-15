package com.baidu.carlife.xiaomi.carplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionPreferencesTest {
    @Test
    fun defaultsUseReceiverDisplay() {
        val advertised = CarPlayResolution(1920, 766, 60)
        assertEquals(advertised, ProjectionPreferences().applyTo(advertised))
    }

    @Test
    fun customResolutionAndFpsAreAppliedWithoutExceedingReceiverLimit() {
        val preferences = ProjectionPreferences(width = 1600, height = 1068, fpsLimit = 60)
        assertEquals(
            CarPlayResolution(1600, 1068, 30),
            preferences.applyTo(CarPlayResolution(1920, 766, 30)),
        )
        assertEquals(
            CarPlayResolution(1600, 1068, 60),
            preferences.applyTo(CarPlayResolution(1920, 766)),
        )
    }
}
