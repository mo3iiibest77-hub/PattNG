package com.v2ray.ang.ui.qsn.domain

import java.security.MessageDigest

/**
 * A previously selected Cloudflare edge for one configuration.
 *
 * The cache stores no credential: [fingerprint] is a SHA-256 over the
 * non-secret transport identity of the profile, and the IP itself is public
 * information.
 */
data class IpSelection(
    val fingerprint: String,
    val ip: String,
    val score: Double = 0.0,
    val latencyMs: Long? = null,
    val jitterMs: Long? = null,
    val downloadKBps: Long? = null,
    val uploadKBps: Long? = null,
    val confidence: Int = 0,
    val validatedAt: Long = 0L,
) {

    /**
     * True when this selection may be reused for [expectedFingerprint].
     *
     * A selection is unusable when it belongs to another configuration, when
     * the IP is not a plain IPv4 literal, when the timestamp is in the future
     * (clock change) or when it is older than [ttlMs].
     */
    fun isUsable(now: Long, expectedFingerprint: String, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        if (fingerprint != expectedFingerprint) return false
        if (!IpAddress.isIpv4(ip)) return false
        if (validatedAt <= 0L) return false
        val age = now - validatedAt
        return age >= 0L && age <= ttlMs
    }

    /**
     * True when the selection is recent enough to be reused without any
     * network round trip. This is what stops a reconnect from scanning.
     */
    fun isFresh(now: Long, freshMs: Long = DEFAULT_FRESH_MS): Boolean {
        if (!IpAddress.isIpv4(ip)) return false
        val age = now - validatedAt
        return validatedAt > 0L && age >= 0L && age <= freshMs
    }

    companion object {
        /** Reuse without a health check for this long. */
        const val DEFAULT_FRESH_MS = 30L * 60L * 1000L

        /** Hard age limit: older than this always triggers a rescan. */
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Builds the cache key for a configuration.
 *
 * The destination server is deliberately NOT part of the key: applying the
 * selected IP rewrites that field, so including it would invalidate the cache
 * the moment it is used. Credentials (UUID, password, subscription URL) are
 * never included either, so the key cannot leak them.
 */
object IpFingerprint {

    /** Bump when the scan algorithm or the meaning of the stored fields changes. */
    const val SCAN_SETTINGS_VERSION = 2

    fun of(
        guid: String,
        configType: String,
        port: Int,
        network: String,
        security: String,
        sni: String?,
        host: String?,
        path: String?,
        xhttpMode: String?,
        rangeFingerprint: String,
        settingsVersion: Int = SCAN_SETTINGS_VERSION,
    ): String {
        val material = listOf(
            "v$settingsVersion",
            guid,
            configType,
            port.toString(),
            network,
            security,
            sni.orEmpty(),
            host.orEmpty(),
            path.orEmpty(),
            xhttpMode.orEmpty(),
            rangeFingerprint,
        ).joinToString("|")
        return sha256(material)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val v = byte.toInt() and 0xFF
            if (v < 0x10) builder.append("0")
            builder.append(Integer.toHexString(v))
        }
        return builder.toString()
    }
}

/** IPv4 literal helpers used by the cache and by range validation. */
object IpAddress {

    fun isIpv4(value: String?): Boolean = parse(value) != null

    /** @return the address as an unsigned 32-bit value, or null when invalid. */
    fun parse(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it.isDigit() }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet < 0 || octet > 255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    fun format(value: Long): String {
        val a = (value shr 24) and 0xFF
        val b = (value shr 16) and 0xFF
        val c = (value shr 8) and 0xFF
        val d = value and 0xFF
        return "$a.$b.$c.$d"
    }
}
