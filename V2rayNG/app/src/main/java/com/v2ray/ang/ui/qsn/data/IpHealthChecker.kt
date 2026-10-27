package com.v2ray.ang.ui.qsn.data

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A cheap, strictly bounded check that a saved edge is still alive.
 *
 * It opens one TCP connection and, when an SNI is known, performs one TLS
 * handshake against the real host name. It transfers no payload, uses the
 * platform trust store (TLS is never weakened) and is capped by a hard timeout,
 * so it cannot turn into a disguised scan.
 *
 * Must be called from a background thread.
 */
object IpHealthChecker {

    const val DEFAULT_TIMEOUT_MS = 2_000

    fun isHealthy(
        ip: String,
        port: Int,
        sni: String?,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (ip.isBlank() || port <= 0 || port > 65535) return false
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = Socket()
            raw.connect(InetSocketAddress(ip, port), timeoutMs)
            if (sni.isNullOrBlank()) return true
            // getDefault() is declared as SocketFactory; the overload that
            // wraps an existing socket lives on SSLSocketFactory.
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket(raw, sni, port, true) as SSLSocket
            ssl = socket
            socket.soTimeout = timeoutMs
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            socket.sslParameters = params
            socket.startHandshake()
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                ssl?.close()
            } catch (_: Exception) {
            }
            try {
                raw?.close()
            } catch (_: Exception) {
            }
        }
    }
}
