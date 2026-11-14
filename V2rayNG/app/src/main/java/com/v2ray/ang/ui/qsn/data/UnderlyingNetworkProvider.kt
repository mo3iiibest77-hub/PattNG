package com.v2ray.ang.ui.qsn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Resolves the active physical underlying network (Wi-Fi / Cellular / Ethernet)
 * excluding VPN interfaces (`NET_CAPABILITY_NOT_VPN`).
 *
 * Combined with [com.v2ray.ang.service.CoreVpnService.configurePerAppProxy]
 * (which excludes QuietStormNG's own package from the VPN TUN), binding OkHttp
 * sockets and DNS to this physical [Network] ensures background discovery
 * fetches (e.g. CloudFront AWS IP ranges) and measurements use the phone's
 * direct physical uplink instead of looping back through an active VPN tunnel.
 */
object UnderlyingNetworkProvider {

    enum class TransportKind(val id: String) {
        WIFI("wifi"),
        CELLULAR("cellular"),
        ETHERNET("ethernet"),
        OTHER("other"),
        UNKNOWN("unknown"),
    }

    /** Returns an active non-VPN internet [Network], preferring Wi-Fi/Cellular/Ethernet. */
    @Suppress("DEPRECATION")
    fun findPhysicalNetwork(context: Context): Network? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.allNetworks.toList()
        } else {
            emptyList()
        }
        if (networks.isEmpty()) return null

        val candidates = networks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) return@mapNotNull null
            network to caps
        }
        if (candidates.isEmpty()) return null

        // Prefer validated Wi-Fi > Cellular > Ethernet > any validated > unvalidated physical
        return candidates.sortedByDescending { (_, caps) ->
            var score = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                score += 100
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 30
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 20
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 10
            score
        }.firstOrNull()?.first
    }

    fun activePhysicalTransport(context: Context): TransportKind {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return TransportKind.UNKNOWN
        val network = findPhysicalNetwork(context) ?: return TransportKind.UNKNOWN
        val caps = cm.getNetworkCapabilities(network) ?: return TransportKind.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportKind.ETHERNET
            else -> TransportKind.OTHER
        }
    }

    /**
     * Returns an [OkHttpClient] bound explicitly to the underlying physical
     * network when one is available, avoiding an active VPN interface.
     */
    fun createDirectHttpClient(
        context: Context,
        connectTimeoutSec: Long = 15L,
        readTimeoutSec: Long = 20L,
    ): OkHttpClient {
        val network = findPhysicalNetwork(context)
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(connectTimeoutSec + readTimeoutSec, TimeUnit.SECONDS)
        if (network != null) {
            val netFactory: SocketFactory? = try {
                network.socketFactory
            } catch (_: Exception) {
                null
            }
            if (netFactory != null) {
                builder.socketFactory(netFactory)
            }
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        network.getAllByName(hostname).toList().ifEmpty {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
        }
        return builder.build()
    }
}
