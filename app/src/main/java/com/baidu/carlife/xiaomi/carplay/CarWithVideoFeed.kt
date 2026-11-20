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

    fun offer(data: ByteBuffer, config: ByteArray, width: Int, height: Int, keyFrame: Boolean, ptsUs: Long, codec: VideoCodec = VideoCodec.H264) {
        current?.offer(data, config, width, height, keyFrame, ptsUs, codec)
    }

    class Subscription {
        private val frames = ArrayBlockingQueue<Frame>(8)
        private val queuedBytes = java.util.concurrent.atomic.AtomicInteger()
        private var needsKeyFrame = true
        private var lastCodec: VideoCodec? = null
        private val encoded = java.util.concurrent.atomic.AtomicLong()
        private val discarded = java.util.concurrent.atomic.AtomicLong()

        fun counts() = encoded.get() to discarded.get()

        // Called by the encoder's single callback thread.
        fun offer(data: ByteBuffer, config: ByteArray, width: Int, height: Int, keyFrame: Boolean, ptsUs: Long, codec: VideoCodec = VideoCodec.H264) {
            encoded.incrementAndGet()
            if (lastCodec != codec) {
                discardPending()
                lastCodec = codec
            }
            if (needsKeyFrame && !keyFrame) {
                discarded.incrementAndGet()
                return
            }
            if (config.size !in 1..AvcPayload.MAX_CONFIGURATION_BYTES ||
                (frames.remainingCapacity() == 0 && !keyFrame)
            ) {
                discardPending()
                discarded.incrementAndGet()
                return
            }
            val payload = try {
                AvcPayload.accessUnit(data)
            } catch (_: IllegalArgumentException) {
                discardPending()
                discarded.incrementAndGet()
                return
            }
            if (frames.remainingCapacity() == 0 ||
                queuedBytes.get() + payload.size + config.size > MAX_QUEUED_BYTES
            ) {
                discardPending()
                if (!keyFrame) {
                    discarded.incrementAndGet()
                    return
                }
            }
            val configuration = if (needsKeyFrame) config else null
            needsKeyFrame = false
            val frame = Frame(payload, configuration, width, height, ptsUs, codec)
            queuedBytes.addAndGet(frame.size)
            frames.add(frame)
        }

        private fun discardPending() {
            while (true) {
                val frame = frames.poll() ?: break
                queuedBytes.addAndGet(-frame.size)
                discarded.incrementAndGet()
            }
            needsKeyFrame = true
        }

        fun poll(timeoutMs: Long): Frame? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)?.also {
            queuedBytes.addAndGet(-it.size)
        }
    }

    data class Frame(
        val payload: ByteArray, val configuration: ByteArray?,
        val width: Int, val height: Int, val ptsUs: Long, val codec: VideoCodec,
    ) {
        val size get() = payload.size + (configuration?.size ?: 0)
    }

    private const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
}
