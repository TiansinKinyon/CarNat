package com.baidu.carlife.xiaomi.carplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2PowerStatusTest {
    @Test
    fun reportsOnlyRequestedFieldsWithTheExpectedWireWidths() {
        val status = Iap2PowerStatus(plugged = true, state = 2, level = 85)
        val fields = status.parameters(setOf(0, 4, 6))
        assertEquals(listOf(4, 6), fields.map { it.first })
        assertArrayEquals(byteArrayOf(1), fields[0].second)
        assertArrayEquals(byteArrayOf(0, 85), fields[1].second)
    }

    @Test
    fun unavailableAndInvalidBatteryFieldsAreNotInvented() {
        assertTrue(Iap2PowerStatus().parameters(setOf(4, 5, 6)).isEmpty())
        assertTrue(Iap2PowerStatus(state = 3, level = 101).parameters(setOf(4, 5, 6)).isEmpty())
    }
}
