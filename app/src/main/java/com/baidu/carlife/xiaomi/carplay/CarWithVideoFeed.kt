package com.baidu.carlife.xiaomi.carplay

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Keeps TCP backpressure off the encoder callback. Resume after a drop at the next key frame. */
internal object CarWithVideoFeed {
    @Volatile
    private var current: Subscription? = null

    @Synchronized
    fun subscribe(): Subscription {
        check(current == null) { "CarWith 已有视频接收端" }
        return Subscription().also { current = it }
    }

    @Synchronized
    fun unsubscribe(subscription: Subscription) {
        if (current === subscription) current = null
    }

    fun offer(data: ByteBuffer, config: ByteArray, width: Int, height: Int, keyFrame: Boolean, ptsUs: Long) {
        current?.offer(data, config, width, height, keyFrame, ptsUs)
    }

    class Subscription {
        private val frames = ArrayBlockingQueue<Frame>(8)
        private var needsKeyFrame = true
        private val encoded = java.util.concurrent.atomic.AtomicLong()
        private val discarded = java.util.concurrent.atomic.AtomicLong()

        fun counts() = encoded.get() to discarded.get()

        // Called by the encoder's single callback thread.
        fun offer(data: ByteBuffer, config: ByteArray, width: Int, height: Int, keyFrame: Boolean, ptsUs: Long) {
            encoded.incrementAndGet()
            if (needsKeyFrame && !keyFrame) {
                discarded.incrementAndGet()
                return
            }
            if (frames.remainingCapacity() == 0) {
                discarded.addAndGet(frames.size.toLong())
                frames.clear()
                needsKeyFrame = true
                if (!keyFrame) return
            }
            val payload = try {
                AvcPayload.accessUnit(data)
            } catch (_: IllegalArgumentException) {
                discarded.addAndGet(frames.size.toLong())
                frames.clear()
                needsKeyFrame = true
                return
            }
            val configuration = if (needsKeyFrame) config else null
            needsKeyFrame = false
            frames.offer(Frame(payload, configuration, width, height, ptsUs))
        }

        fun poll(timeoutMs: Long): Frame? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    data class Frame(
        val payload: ByteArray, val configuration: ByteArray?,
        val width: Int, val height: Int, val ptsUs: Long,
    )
}
