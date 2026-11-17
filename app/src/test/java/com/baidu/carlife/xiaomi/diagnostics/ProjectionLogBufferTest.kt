package com.baidu.carlife.xiaomi.diagnostics

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionLogBufferTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun diskLockDoesNotBlockEventProducersOrCrashReporting() {
        val directory = File(temp.root, "logs")
        assertTrue(directory.createNewFile())
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val diskHeld = CountDownLatch(1)
        val releaseDisk = CountDownLatch(1)
        val buffer = ProjectionLogBuffer(RollingLogFiles(directory), writer) {
            diskHeld.countDown()
            releaseDisk.await()
        }
        try {
            buffer.enqueue("fail")
            assertTrue(diskHeld.await(5, TimeUnit.SECONDS))
            caller.submit {
                buffer.enqueue("while busy")
                buffer.tryWriteCrash("crash")
            }.get(5, TimeUnit.SECONDS)
        } finally {
            releaseDisk.countDown()
            caller.shutdownNow()
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun boundsBacklogAndFlushesNewestEntriesInOrderForASnapshot() {
        val jobs = ArrayDeque<Runnable>()
        val buffer = ProjectionLogBuffer(RollingLogFiles(temp.root), Executor { jobs.addLast(it) }, capacity = 2) {
            throw AssertionError(it)
        }
        buffer.enqueue("first")
        buffer.enqueue("second")
        buffer.enqueue("third")

        assertEquals(1, jobs.size)
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
        val snapshot = buffer.snapshot()
        assertFalse(snapshot.contains("first"))
        assertTrue(snapshot.contains("已略过 1 条记录"))
        assertTrue(snapshot.indexOf("second") in 0 until snapshot.indexOf("third"))
        jobs.removeFirst().run()
        assertEquals(snapshot, buffer.snapshot())

        buffer.enqueue("fourth")
        jobs.removeFirst().run()
        assertTrue(buffer.snapshot().endsWith("fourth\n"))
    }

    @Test
    fun reportsStorageFailureOnceAndResumesWhenDirectoryIsAvailable() {
        val directory = File(temp.root, "logs")
        assertTrue(directory.createNewFile())
        var failures = 0
        val buffer = ProjectionLogBuffer(RollingLogFiles(directory), Executor { it.run() }) { failures++ }
        buffer.enqueue("first")
        buffer.enqueue("second")
        assertEquals(1, failures)

        assertTrue(directory.delete())
        buffer.enqueue("recovered")
        assertTrue(buffer.snapshot().endsWith("recovered\n"))
    }
}
