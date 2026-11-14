package com.v2ray.ang.senpai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.util.LogUtil
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Ensures Discovery and background CDN network probes strictly use the device's active physical
 * uplink (cellular or Wi-Fi) with [NetworkCapabilities.NET_CAPABILITY_NOT_VPN], preventing any
 * routing loop through the active TUN interface and halting cleanly if physical connectivity drops.
 */
object PhysicalNetworkGuard {

    private const val TAG = "PhysicalNetworkGuard"

    @Volatile
    private var lastObservedPhysical: Network? = null

    fun findActivePhysicalNetwork(context: Context): Network? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val active = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        var bestNetwork: Network? = null
        var bestScore = -1

        for (network in active) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            var score = 1
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 10
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 3
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 2

            if (score > bestScore) {
                bestScore = score
                bestNetwork = network
            }
        }

        if (bestNetwork != null) {
            lastObservedPhysical = bestNetwork
        }
        return bestNetwork
    }

    fun hasPhysicalInternet(context: Context): Boolean = findActivePhysicalNetwork(context) != null

    /**
     * Builds an [OkHttpClient] pinned explicitly to a non-VPN physical [Network] (both TCP sockets
     * and DNS lookups bypass the VPN tunnel). Returns `null` when [requirePhysical] is true and
     * no physical internet network is available.
     */
    fun createPhysicalOkHttpClient(
        context: Context,
        connectTimeoutSec: Long = 4L,
        readTimeoutSec: Long = 4L,
        requirePhysical: Boolean = false,
    ): OkHttpClient? {
        val physical = findActivePhysicalNetwork(context)
        if (physical == null && requirePhysical) {
            return null
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)

        if (physical != null) {
            builder.socketFactory(physical.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return runCatching { physical.getAllByName(hostname).toList() }
                        .getOrElse { Dns.SYSTEM.lookup(hostname) }
                }
            })
        }
        return builder.build()
    }

    /**
     * Watches physical non-VPN internet connectivity while Discovery is running so that loss of
     * underlying cellular/Wi-Fi stops probing without disturbing the VPN tunnel itself.
     */
    class Watcher(
        context: Context,
        private val onPhysicalLost: () -> Unit,
    ) {
        private val appContext = context.applicationContext
        private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        private var callback: ConnectivityManager.NetworkCallback? = null

        fun start() {
            val manager = cm ?: return
            if (callback != null) return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lastObservedPhysical = network
                }

                override fun onLost(network: Network) {
                    if (findActivePhysicalNetwork(appContext) == null) {
                        LogUtil.w(TAG, "Physical non-VPN internet lost during discovery; halting discovery.")
                        onPhysicalLost()
                    }
                }
            }
            callback = cb
            runCatching { manager.registerNetworkCallback(request, cb) }
                .onFailure { LogUtil.w(TAG, "Unable to register physical network watcher: ${it.message}") }
        }

        fun stop() {
            val cb = callback ?: return
            callback = null
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
    }
}
