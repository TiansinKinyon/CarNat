package com.baidu.carlife.xiaomi.carplay

import java.io.Closeable
import java.io.IOException
import java.net.Socket

/** Owns the TCP sockets of one receiver attempt, including sockets still connecting. */
internal class CarPlaySockets : Closeable {
    private val sockets = mutableListOf<Socket>()
    private var closed = false

    @Synchronized
    fun open(): Socket {
        if (closed) throw IOException("CarPlay 会话已停止")
        return Socket().also(sockets::add)
    }

    @Synchronized
    override fun close() {
        closed = true
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
