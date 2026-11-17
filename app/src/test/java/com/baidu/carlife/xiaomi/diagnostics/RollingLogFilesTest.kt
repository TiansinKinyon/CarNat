package com.baidu.carlife.xiaomi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingLogFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun keepsOnlyNewestEntriesInChronologicalOrderAcrossRestarts() {
        val initial = RollingLogFiles(temp.root, fileLimit = 16, fileCount = 3)
        repeat(10) { index -> initial.append("line-$index\n") }
        val reopened = RollingLogFiles(temp.root, fileLimit = 16, fileCount = 3)
        assertEquals((4..9).joinToString("") { "line-$it\n" }, reopened.snapshot())
        val files = temp.root.listFiles().orEmpty()
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 16 })
    }

    @Test
    fun rotatesByUtf8Bytes() {
        val files = RollingLogFiles(temp.root, fileLimit = 6, fileCount = 2)
        repeat(3) { files.append("日志") }
        assertEquals("日志日志", files.snapshot())
        assertTrue(temp.root.listFiles().orEmpty().all { it.length() == 6L })
    }

    @Test
    fun readsOnlyCompleteRecentLinesFromAnOversizedFile() {
        temp.newFile("CarNat-0.log").writeText("旧".repeat(100) + "\n最近\n")
        val files = RollingLogFiles(temp.root, fileLimit = 10)
        assertEquals("最近\n", files.snapshot())
    }

    @Test
    fun preservesTheFirstLineWhenTheTailStartsOnALineBoundary() {
        temp.newFile("CarNat-0.log").writeText("旧\n最近\n")
        val files = RollingLogFiles(temp.root, fileLimit = 7)
        assertEquals("最近\n", files.snapshot())
    }
}
