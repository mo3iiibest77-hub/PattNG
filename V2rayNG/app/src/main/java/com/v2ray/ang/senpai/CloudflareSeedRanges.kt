package com.v2ray.ang.senpai

/**
 * Canonical 46 user-curated Cloudflare seed CIDRs and IPv4 CIDR matching helpers.
 *
 * These seeds are kept intact permanently in regular scanning and are never removed
 * or overwritten by ISP Discovery runs.
 */
object CloudflareSeedRanges {

    /**
     * Exact 46 Cloudflare seed ranges validated for user scanning.
     */
    val SEED_CIDRS: List<String> = listOf(
        "5.10.214.0/23",
        "8.34.146.0/24",
        "8.34.70.0/23",
        "8.35.211.0/24",
        "8.39.125.0/24",
        "8.39.214.0/23",
        "8.47.69.0/24",
        "23.227.38.0/23",
        "23.227.60.0/24",
        "37.153.171.0/24",
        "31.185.108.0/24",
        "45.128.76.0/24",
        "45.192.222.0/23",
        "45.192.224.0/24",
        "45.196.29.0/24",
        "49.238.236.0/22",
        "66.235.200.0/24",
        "82.26.156.0/24",
        "88.216.69.0/24",
        "185.18.184.0/24",
        "170.168.7.0/24",
        "156.224.73.0/24",
        "173.0.92.0/24",
        "162.158.0.0/15",
        "159.242.242.0/24",
        "188.95.12.0/24",
        "185.156.19.0/24",
        "204.69.207.0/24",
        "176.103.113.0/24",
        "204.153.16.0/24",
        "94.156.10.0/24",
        "103.31.79.0/24",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "104.29.124.0/22",
        "109.234.211.0/24",
        "154.81.141.0/24",
        "162.251.82.0/24",
        "185.146.172.0/23",
        "185.149.135.0/24",
        "185.158.133.0/24",
        "192.71.82.0/24",
        "5.10.244.0/22",
        "8.6.112.0/24",
        "8.39.204.0/23",
        "45.135.235.0/24",
    )

    /**
     * Core official Cloudflare IPv4 blocks used alongside [SEED_CIDRS] for domain-to-CDN
     * classification when DNS resolves to Cloudflare's public edge.
     */
    val OFFICIAL_CLASSIFICATION_CIDRS: List<String> = listOf(
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "108.162.192.0/18",
        "131.0.72.0/22",
        "141.101.64.0/18",
        "162.158.0.0/15",
        "172.64.0.0/13",
        "173.245.48.0/20",
        "188.114.96.0/20",
        "190.93.240.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
    )

    /**
     * Parses a valid IPv4 CIDR string (`a.b.c.d/prefix`), returning normalized `ip/prefix`
     * or null when invalid.
     */
    fun normalizeIpv4Cidr(raw: String): String? {
        val trimmed = raw.trim()
        val parts = trimmed.split("/")
        if (parts.size != 2) return null
        val ipLong = ipv4ToLong(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 1..32) return null
        val mask = prefixToMask(prefix)
        val network = ipLong and mask
        return "${longToIpv4(network)}/$prefix"
    }

    /**
     * Returns true when [ip] is a valid IPv4 address contained in [cidr].
     */
    fun ipv4InCidr(ip: String, cidr: String): Boolean {
        val ipLong = ipv4ToLong(ip.trim()) ?: return false
        val parts = cidr.trim().split("/")
        if (parts.size != 2) return false
        val netLong = ipv4ToLong(parts[0]) ?: return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val mask = prefixToMask(prefix)
        return (ipLong and mask) == (netLong and mask)
    }

    /**
     * Checks whether [ip] falls inside any CIDR in [cidrs].
     */
    fun matchesAnyCidr(ip: String, cidrs: Iterable<String>): Boolean {
        if (ipv4ToLong(ip.trim()) == null) return false
        return cidrs.any { ipv4InCidr(ip, it) }
    }

    fun ipv4ToLong(ip: String): Long? {
        val octets = ip.split(".")
        if (octets.size != 4) return null
        var acc = 0L
        for (octet in octets) {
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            acc = (acc shl 8) or value.toLong()
        }
        return acc
    }

    fun longToIpv4(value: Long): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    private fun prefixToMask(prefix: Int): Long =
        if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
}
