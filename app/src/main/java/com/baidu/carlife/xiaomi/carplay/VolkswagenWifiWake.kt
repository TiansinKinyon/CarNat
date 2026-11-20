package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import java.net.Inet4Address

/** Wakes the existing connection service once per association to a verified MIB hotspot. */
internal object VolkswagenWifiWake {
    private val main = Handler(Looper.getMainLooper())
    private var attempted: Network? = null
    private var check: Runnable? = null

    fun checkWhenResumed() {
        check?.let { main.removeCallbacks(it); main.post(it) }
    }
    private val observed = java.util.concurrent.ConcurrentHashMap<Network, String>()

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val check = Runnable {
            val network = matchingNetwork(context) ?: return@Runnable
            if (attempted != network) {
                if (CarlifeConnectService.state.value.active) {
                    attempted = network
                } else if (CarlifeConnectService.wakeVolkswagen(context, network)) {
                    attempted = network
                }
            }
        }
        this.check = check
        val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            private fun schedule() {
                main.removeCallbacks(check)
                main.postDelayed(check, 750)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val ssid = (capabilities.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") observed.remove(network)
                else observed[network] = ssid
                schedule()
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = schedule()
            override fun onLost(network: Network) {
                observed.remove(network)
                main.post { if (attempted == network) attempted = null }
                schedule()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { ProjectionLog.failure("无法监听大众车机热点", it) }
    }

    fun remember(store: VolkswagenPeerStore, network: Network) {
        observed[network]?.let(store::rememberSsid)
    }

    @SuppressLint("MissingPermission")
    fun matchingNetwork(context: Context): Network? {
        if (HeadUnitProfile.load(context) != HeadUnitProfile.VOLKSWAGEN ||
            ConnectionMode.load(context) != ConnectionMode.CAR_WITH ||
            missingReceiverPermissions(context).isNotEmpty()
        ) return null
        val settings = ReceiverSettings.load(context)
        if (!settings.valid || !ReceiverSettings.validAddress(settings.receiverAddress)) return null
        val bonded = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.any { it.address.equals(settings.receiverAddress, ignoreCase = true) }
        }.getOrNull()
        if (bonded != true) return null
        val store = VolkswagenPeerStore(context.noBackupFilesDir, settings.address, settings.receiverAddress)
        val ssid = store.readSsid() ?: return null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = observed.entries.singleOrNull { (network, name) ->
            name == ssid && connectivity.getLinkProperties(network)?.linkAddresses
                ?.any { it.address is Inet4Address } == true
        }?.key ?: return null
        val certificate = store.read() ?: return null
        val pairing = VehiclePairingStore(context.noBackupFilesDir, settings.address,
            settings.receiverAddress.uppercase(java.util.Locale.ROOT), certificate, ReceiverProtocol.VOLKSWAGEN)
        return network.takeIf { pairing.read() != null }
    }
}
