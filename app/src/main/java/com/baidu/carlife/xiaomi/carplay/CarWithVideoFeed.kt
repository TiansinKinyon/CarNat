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

        // Called by the encoder's single callback thread.
        fun offer(data: ByteBuffer, config: ByteArray, width: Int, height: Int, keyFrame: Boolean, ptsUs: Long) {
            if (needsKeyFrame && !keyFrame) return
            if (frames.remainingCapacity() == 0) {
                frames.clear()
                needsKeyFrame = true
                if (!keyFrame) return
            }
            val payload = try {
                AvcPayload.accessUnit(data)
            } catch (_: IllegalArgumentException) {
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
