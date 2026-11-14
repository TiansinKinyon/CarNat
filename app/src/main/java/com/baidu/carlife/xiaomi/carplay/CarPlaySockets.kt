package com.baidu.carlife.xiaomi.carplay

import android.net.Network
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Owns the TCP sockets of one receiver attempt, including sockets still connecting. */
internal class CarPlaySockets : Closeable {
    private val sockets = mutableListOf<Socket>()
    private val datagrams = mutableListOf<DatagramSocket>()
    private var closed = false
    private var network: Network? = null
    private var wifiInterface: String? = null

    @Synchronized
    fun useNetwork(network: Network, interfaceName: String?) {
        if (closed) throw IOException("CarPlay 会话已停止")
        this.network = network
        wifiInterface = interfaceName
    }

    fun resolve(host: String): InetAddress {
        val wifi = network ?: return InetAddress.getByName(host)
        val unscopedHost = host.substringBefore('%')
        val address = wifi.getByName(unscopedHost)
        if (address !is Inet6Address || !address.isLinkLocalAddress) return address
        val index = wifiInterface?.let(NetworkInterface::getByName)?.index
            ?: throw IOException("无法确定车机 Wi-Fi 的 IPv6 接口")
        return Inet6Address.getByAddress(null, address.address, index)
    }

    @Synchronized
    fun openDatagram(): DatagramSocket {
        if (closed) throw IOException("CarPlay 会话已停止")
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(0))
            network?.bindSocket(socket)
            datagrams.add(socket)
            return socket
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    @Synchronized
    fun open(): Socket {
        if (closed) throw IOException("CarPlay 会话已停止")
        return (network?.socketFactory?.createSocket() ?: Socket()).also(sockets::add)
    }

    @Synchronized
    override fun close() {
        closed = true
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        datagrams.forEach(DatagramSocket::close)
        datagrams.clear()
    }
}
