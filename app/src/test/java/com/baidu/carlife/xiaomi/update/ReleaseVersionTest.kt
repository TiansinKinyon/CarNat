package com.baidu.carlife.xiaomi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun normalizesExistingVersionAndOrdersNumerically() {
        assertEquals(ReleaseVersion.parse("1.1"), ReleaseVersion.parse("v1.1.0"))
        assertTrue(requireNotNull(ReleaseVersion.parse("v1.10.0")) > requireNotNull(ReleaseVersion.parse("1.9.8")))
    }

    @Test
    fun rejectsPrereleasesAndInvalidTags() {
        assertNull(ReleaseVersion.parse("v1.2.0-beta"))
        assertNull(ReleaseVersion.parse("../v1.2.0"))
        assertNull(ReleaseVersion.parse("v2147483648.0.0"))
    }
}
