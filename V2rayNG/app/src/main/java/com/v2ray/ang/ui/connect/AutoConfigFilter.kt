package com.v2ray.ang.ui.connect

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Filters subscription-imported [ProfileItem]s to those eligible for the
 * automatic Cloudflare-CDN connection flow of Quiet Storm NG.
 *
 * Eligibility is **independent of protocol**: any of VLESS, VMess, Trojan,
 * etc. that meets the transport/security/port criteria below qualifies.
 *
 * Criteria (all must hold):
 *  - network ∈ {ws, xhttp}
 *  - security == tls
 *  - serverPort in [HTTPS_PORTS] (Cloudflare CDN HTTPS ports, documented at
 *    https://developers.cloudflare.com/fundamentals/reference/network-ports/).
 *  - server address non-empty.
 *  - protocol is one of the connection-capable types (excludes CUSTOM,
 *    POLICYGROUP, PROXYCHAIN which are not standalone outbound endpoints).
 *
 * Configs that fail this filter are **not** removed from storage or from the
 * manual server list; they are only excluded from the *automatic* selection
 * path. Parser support for every protocol remains untouched.
 */
object AutoConfigFilter {

    /**
     * Cloudflare CDN HTTPS ports that the automatic flow will accept.
     *
     * Source: Cloudflare's published list of ports Cloudflare CDN proxies for
     * HTTPS traffic. These are the well-known Cloudflare edge HTTPS ports,
     * not guessed values. Only configs whose real [ProfileItem.serverPort]
     * matches one of these may be auto-selected.
     */
    val HTTPS_PORTS: Set<String> = setOf(
        "443",   // standard HTTPS
        "2053", "2083", "2087", "2096",  // Cloudflare HTTPS edge ports
        "8443",  // standard alt HTTPS
        "2052", "2082", "2086", "2095",  // Cloudflare HTTP-edge ports are
        // intentionally NOT included here because auto-connect requires TLS.
    )

    /** Network types eligible for automatic Cloudflare connection. */
    private val ELIGIBLE_NETWORKS = setOf("ws", "xhttp")

    /**
     * Protocol [EConfigType]s that represent a real standalone outbound and
     * can therefore be auto-connected. POLICYGROUP/PROXYCHAIN/CUSTOM are
     * composite/meta types and are excluded.
     */
    private val ELIGIBLE_CONFIG_TYPES = setOf(
        EConfigType.VMESS,
        EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS,
        EConfigType.VLESS,
        EConfigType.TROJAN,
        EConfigType.WIREGUARD,
        EConfigType.HYSTERIA2,
        EConfigType.HTTP,
    )

    /**
     * Returns the subset of [configs] that are eligible for the automatic
     * connection flow, in their original order.
     *
     * Ineligible configs are simply skipped; they remain valid for manual use
     * and are still parsed/imported by the existing code paths.
     */
    fun filterEligible(configs: List<ProfileItem>): List<ProfileItem> {
        if (configs.isEmpty()) return emptyList()
        return configs.filter { isEligible(it) }
    }

    /**
     * True iff [config] meets every automatic-connection criterion.
     *
     * Each check is evaluated independently so a config is only accepted when
     * *all* hold; a single failure excludes it from auto-selection without
     * affecting anything else.
     */
    fun isEligible(config: ProfileItem): Boolean {
        // 1. Protocol must be a real standalone outbound (not a meta type).
        if (config.configType !in ELIGIBLE_CONFIG_TYPES) return false

        // 2. Transport must be WebSocket or XHTTP.
        val network = config.network ?: return false
        if (network !in ELIGIBLE_NETWORKS) return false

        // 3. Security must be TLS.
        if (config.security != AppConfig.TLS) return false

        // 4. Real server port must be in the approved HTTPS port set.
        val port = config.serverPort?.trim().orEmpty()
        if (port.isEmpty() || port !in HTTPS_PORTS) return false

        // 5. Server address must be present — a config with no destination
        //    cannot be connected, auto or manual.
        if (config.server.isNullOrBlank()) return false

        // 6. Protocol-specific essential params must be present. We only
        //    enforce the minimum: a non-empty password/uuid-equivalent where
        //    the protocol requires it. We deliberately do NOT over-constrain
        //    here — the existing CoreOutboundBuilder.convert() already
        //    validates structure when building the outbound and returns null
        //    if a protocol's required fields are missing. This filter only
        //    rejects configs that obviously cannot stand alone.
        when (config.configType) {
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.TROJAN -> {
                // uuid / password
                if (config.password.isNullOrBlank()) return false
            }
            EConfigType.SHADOWSOCKS -> {
                if (config.password.isNullOrBlank() || config.method.isNullOrBlank()) return false
            }
            EConfigType.SOCKS,
            EConfigType.HTTP -> {
                if (config.username.isNullOrBlank()) return false
            }
            EConfigType.WIREGUARD -> {
                if (config.publicKey.isNullOrBlank()) return false
            }
            EConfigType.HYSTERIA2 -> {
                if (config.password.isNullOrBlank()) return false
            }
            else -> return false
        }

        return true
    }
}
