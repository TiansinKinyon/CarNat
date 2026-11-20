package com.baidu.carlife.xiaomi.carplay

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** One advertisement and one consumable invitation for the selected Wi-Fi network. */
@TargetApi(33)
internal class CarPlayControlInvitation(
    private val connectivity: ConnectivityManager,
    private val nsd: NsdManager,
    val network: Network,
    private val localAddress: InetAddress,
    private val deviceId: String,
    private val instanceName: String = "sender",
    private val sourceVersion: String = SOURCE_VERSION,
) : Closeable {
    class Invite(val host: InetAddress, val receivedAt: Long)

    private val server = ServerSocket()
    private val lock = java.lang.Object()
    private var closed = false
    private var registered = false
    private var registrationFailed = false
    private var client: Socket? = null
    private val invites = LinkedHashMap<InetAddress, Invite>()
    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(service: NsdServiceInfo) {
            synchronized(lock) {
                if (!closed) registered = true
                else runCatching { nsd.unregisterService(this) }
                lock.notifyAll()
            }
        }
        override fun onRegistrationFailed(service: NsdServiceInfo, error: Int) {
            synchronized(lock) {
                registrationFailed = true
                lock.notifyAll()
            }
        }
        override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(service: NsdServiceInfo, error: Int) = Unit
    }

    fun start() {
        try {
            server.bind(InetSocketAddress(localAddress, 0), 4)
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = instanceName
                serviceType = "_carplay-ctrl._tcp"
                port = server.localPort
                network = this@CarPlayControlInvitation.network
                setAttribute("id", deviceId)
                setAttribute("srcvers", sourceVersion)
            }, NsdManager.PROTOCOL_DNS_SD, listener)
            Thread(::accept, "CarPlay control invitation").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            close()
            throw IOException("无法发布 CarPlay 控制服务", failure)
        }
    }

    fun await(expected: InetAddress?, timeoutMs: Long = 25_000L): InetAddress? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (!closed && !registrationFailed) {
                val now = SystemClock.elapsedRealtime()
                prune(now)
                val candidate = if (expected == null) invites.values.singleOrNull() else invites[expected]
                if (registered && candidate != null) {
                    checkNetwork()
                    return candidate.host
                }
                if (now >= deadline) break
                try {
                    lock.wait(deadline - now)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("等待车机邀请已取消")
                }
            }
        }
        return null
    }

    fun peek(): Invite? = synchronized(lock) {
        prune(SystemClock.elapsedRealtime())
        if (closed || !registered || registrationFailed) return@synchronized null
        invites.values.singleOrNull()?.also { checkNetwork() }
    }

    fun discard(invite: Invite) = synchronized(lock) {
        if (invites[invite.host] === invite) invites.remove(invite.host)
    }

    fun requireFresh(host: InetAddress, consume: Boolean = false, expected: Invite? = null) {
        synchronized(lock) {
            checkNetwork()
            prune(SystemClock.elapsedRealtime())
            if (closed || host !in invites || (expected != null && invites[host] !== expected)) {
                throw IOException("大众车机邀请已过期或端点已变化")
            }
            if (consume) invites.remove(host)
        }
    }

    private fun checkNetwork() {
        if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            connectivity.getLinkProperties(network)?.linkAddresses?.none { it.address == localAddress } != false
        ) throw IOException("大众车机 Wi-Fi 已变化")
    }

    private fun accept() {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    synchronized(lock) {
                        if (closed) return
                        client = socket
                    }
                    try {
                        handle(socket)
                    } catch (_: IOException) {
                        // Discard this request. Keep the current advertisement available.
                    } finally {
                        synchronized(lock) { client = null }
                    }
                }
            }
        } catch (_: IOException) {
            close()
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(8192)
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        for (index in header.indices) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException()
            socket.soTimeout = remaining.toInt()
            val byte = input.read()
            if (byte < 0) return
            header[index] = byte.toByte()
            if (index >= 3 && header[index - 3] == 13.toByte() && header[index - 2] == 10.toByte() &&
                header[index - 1] == 13.toByte() && byte == 10
            ) {
                val requestLine = String(header, 0, index + 1, Charsets.US_ASCII).substringBefore("\r\n")
                if (!isConnectRequest(requestLine)) return
                checkNetwork()
                val host = socket.inetAddress
                if (host.isLoopbackAddress || host.isAnyLocalAddress || host.isMulticastAddress) return
                synchronized(lock) {
                    if (closed) return
                    socket.getOutputStream().write(RESPONSE)
                    val now = SystemClock.elapsedRealtime()
                    prune(now)
                    if (host in invites || invites.size < MAX_INVITATIONS) invites[host] = Invite(host, now)
                    lock.notifyAll()
                }
                return
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            invites.clear()
            runCatching { server.close() }
            runCatching { client?.close() }
            lock.notifyAll()
        }
        runCatching { nsd.unregisterService(listener) }
    }

    private fun prune(now: Long) {
        invites.entries.removeAll { now - it.value.receivedAt >= INVITE_LIFETIME }
    }

    companion object {
        const val SOURCE_VERSION = "770.8.1"
        private const val MAX_INVITATIONS = 8
        private const val INVITE_LIFETIME = 20_000L
        private val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

        fun isConnectRequest(line: String): Boolean {
            val fields = line.split(' ')
            return fields.size == 3 && fields[0] == "GET" &&
                fields[1].substringBefore('?') == "/ctrl-int/1/connect" &&
                fields[2] in setOf("HTTP/1.0", "HTTP/1.1")
        }
    }
}
