package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/** Why a configuration cannot be used for Cloudflare scanning. */
enum class Eligibility {
    Eligible,
    NotTls,
    UnsupportedTransport,
    UnsupportedPort,
    MissingServer,
}

/**
 * Eligibility rules for the automatic Cloudflare flow.
 *
 * These mirror the rules the scanner already relies on (WS or XHTTP over TLS
 * on a Cloudflare HTTPS port). They are deliberately about transport, not about
 * the VPN protocol: a VLESS, VMess, Trojan or any other profile is eligible as
 * long as its transport matches.
 *
 * A profile that fails these rules is still a valid configuration: it is stored
 * and usable manually, it just cannot be auto-connected.
 */
object ManualConfigRules {

    /** Cloudflare edge ports that serve TLS. */
    val HTTPS_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443)

    fun eligibility(network: String?, security: String?, port: Int, server: String?): Eligibility {
        if (server.isNullOrBlank()) return Eligibility.MissingServer
        if (!security.equals("tls", ignoreCase = true)) return Eligibility.NotTls
        val ws = network.equals("ws", ignoreCase = true)
        val xhttp = network.equals("xhttp", ignoreCase = true)
        if (!ws && !xhttp) return Eligibility.UnsupportedTransport
        if (port !in HTTPS_PORTS) return Eligibility.UnsupportedPort
        return Eligibility.Eligible
    }

    fun isEligible(network: String?, security: String?, port: Int, server: String?): Boolean =
        eligibility(network, security, port, server) == Eligibility.Eligible

    /**
     * Canonical, credential-bearing identity used only for duplicate detection.
     *
     * It is hashed before it leaves this function, so nothing reversible is
     * ever stored or logged.
     */
    /**
     * Duplicate-detection key for a stored profile.
     *
     * Uses only the fields that define the connection identity, so renaming a
     * profile or re-importing it does not make it look like a new
     * configuration. The credential is folded in so two different keys on the
     * same server are not treated as duplicates.
     */
    fun keyOf(profile: ProfileItem): String = contentKey(
        configType = profile.configType.name,
        server = profile.server,
        port = profile.serverPort?.toIntOrNull() ?: 0,
        network = profile.network,
        security = profile.security,
        sni = profile.sni,
        host = profile.host,
        path = profile.path,
        credential = listOfNotNull(
            profile.password,
            profile.username,
            profile.secretKey,
            profile.publicKey,
        ).joinToString(":"),
    )

    fun contentKey(
        configType: String,
        server: String?,
        port: Int,
        network: String?,
        security: String?,
        sni: String?,
        host: String?,
        path: String?,
        credential: String?,
    ): String {
        val material = listOf(
            configType,
            server.orEmpty(),
            port.toString(),
            network.orEmpty(),
            security.orEmpty(),
            sni.orEmpty(),
            host.orEmpty(),
            path.orEmpty(),
            credential.orEmpty(),
        ).joinToString("|")
        return IpFingerprint.sha256(material)
    }
}
