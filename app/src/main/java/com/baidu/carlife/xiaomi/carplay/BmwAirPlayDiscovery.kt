package com.baidu.carlife.xiaomi.carplay

import android.annotation.TargetApi
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Only discovers services on the network obtained from the authenticated car's hotspot request. */
internal object BmwAirPlayDiscovery {
    fun find(manager: NsdManager?, network: Network, link: LinkProperties): Pair<String, Int> {
        if (manager != null && Build.VERSION.SDK_INT >= 34) {
            val discovered = try {
                discover(manager, network)?.let { resolve(manager, network, it) }
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (discovered != null) return discovered
        }
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        return (gateway?.hostAddress ?: throw IOException("车机热点未公布 AirPlay 端点")) to 7000
    }

    @TargetApi(34)
    private fun discover(manager: NsdManager, network: Network): NsdServiceInfo? {
        val services = linkedMapOf<String, NsdServiceInfo>()
        val failed = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = failed.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.network != network) return
                synchronized(services) {
                    // Two candidates are enough to detect ambiguity.
                    if (services.size < 2) services[service.serviceName] = service
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(services) { services.remove(service.serviceName) }
            }
        }
        manager.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, network, Executor { it.run() }, listener)
        try {
            wait(failed)
            return synchronized(services) { services.values.singleOrNull() }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    @TargetApi(34)
    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, network: Network, service: NsdServiceInfo): Pair<String, Int>? {
        val done = CountDownLatch(1)
        val endpoint = AtomicReference<Pair<String, Int>>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done.countDown()
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val addresses = resolved.hostAddresses
                val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
                if (resolved.network == network && host != null && resolved.port in 1..65535) {
                    endpoint.set(host to resolved.port)
                }
                done.countDown()
            }
        }
        manager.resolveService(service, Executor { it.run() }, listener)
        try {
            wait(done)
            return endpoint.get()
        } finally {
            if (done.count > 0) runCatching { manager.stopServiceResolution(listener) }
        }
    }

    private fun wait(latch: CountDownLatch) {
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("车机端点发现已取消")
        }
    }
}
