package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

/**
 * Preserves a profile's true origin domain across CDN IP rewrites ([CloudflareScanner.applyBestIp]),
 * which sets [ProfileItem.server] to the winning edge IPv4 address.
 */
object OriginDomainStore {

    private const val KEY_PREFIX = "qsn_origin_domain_"

    fun rememberIfDomain(guid: String, profile: ProfileItem) {
        val candidates = listOf(profile.host, profile.sni, profile.server)
        for (raw in candidates) {
            val clean = normalizeHost(raw) ?: continue
            if (!Utils.isPureIpAddress(clean)) {
                MmkvManager.encodeSettings(KEY_PREFIX + guid, clean)
                return
            }
        }
    }

    fun getOriginDomain(guid: String, profile: ProfileItem? = null): String? {
        val saved = MmkvManager.decodeSettingsString(KEY_PREFIX + guid)
        if (!saved.isNullOrBlank() && !Utils.isPureIpAddress(saved)) {
            return saved
        }
        profile ?: return null
        return resolveDomainFromProfile(profile)
    }

    fun resolveDomainFromProfile(profile: ProfileItem): String? {
        val server = normalizeHost(profile.server)
        if (server != null && !Utils.isPureIpAddress(server)) return server
        val sni = normalizeHost(profile.sni)
        if (sni != null && !Utils.isPureIpAddress(sni)) return sni
        val host = normalizeHost(profile.host)
        if (host != null && !Utils.isPureIpAddress(host)) return host
        return server ?: sni ?: host
    }

    private fun normalizeHost(raw: String?): String? {
        val value = raw?.trim()?.substringBefore(',')?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() }
    }
}
