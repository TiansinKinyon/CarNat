package com.baidu.carlife.xiaomi.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverWifiTest {
    @Test
    fun parsesStandardAndShiftedStartSession() {
        for (offset in 0..1) {
            val wifi = requireNotNull(ReceiverWifi.fromStartSession(mapOf(
                offset to "Car hotspot\u0000".toByteArray(),
                offset + 1 to "test-passphrase\u0000".toByteArray(),
                offset + 4 to byteArrayOf(4),
            ), offset))
            assertEquals("Car hotspot", wifi.ssid)
            assertEquals("test-passphrase", wifi.passphrase)
            assertEquals(4, wifi.security)
        }
    }

    @Test
    fun parsesLegacyConfigWithSeparateSecurityField() {
        val wifi = requireNotNull(ReceiverWifi.fromConfiguration(mapOf(
            1 to "Car hotspot".toByteArray(),
            2 to "test-passphrase".toByteArray(),
            3 to byteArrayOf(2),
            4 to byteArrayOf(36),
        )))
        assertEquals(2, wifi.security)
    }

    @Test
    fun anEndpointWithoutCredentialsCanUseTheCurrentNetwork() {
        assertNull(ReceiverWifi.fromStartSession(mapOf(3 to "192.0.2.10".toByteArray()), 0))
    }
}
