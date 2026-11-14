package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Enforces NovaPulse CDN-only configuration policy:
 * Allows ONLY VLESS + TLS + supported CDN transports (`xhttp`, `ws`, `splithttp`) over HTTPS/CDN
 * ports, filtering out Reality, plaintext, Shadowsocks, VMess, Trojan, and non-CDN configs.
 */
object AutoConfigFilter {

    private val SUPPORTED_TRANSPORTS = setOf("xhttp", "ws", "splithttp")
    private val SUPPORTED_HTTPS_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443)

    fun isCdnScanEligible(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        if (profile.configType != EConfigType.VLESS) return false
        val security = profile.security?.trim()?.lowercase()
        if (security != "tls") return false
        val network = profile.network?.trim()?.lowercase() ?: return false
        if (network !in SUPPORTED_TRANSPORTS) return false
        val port = profile.serverPort?.trim()?.toIntOrNull() ?: return false
        return port in SUPPORTED_HTTPS_PORTS
    }

    /**
     * Picks the preferred configuration GUID from candidate profiles:
     * 1) Currently selected GUID if it is CDN-eligible
     * 2) First CDN-eligible GUID in the active subscription/list
     * 3) Currently selected GUID (fallback if still present)
     * 4) First available GUID
     */
    fun pickBestConnectGuid(
        selectedGuid: String?,
        profilesInOrder: List<Pair<String, ProfileItem?>>,
    ): String? {
        val map = profilesInOrder.toMap()
        if (!selectedGuid.isNullOrBlank() && isCdnScanEligible(map[selectedGuid])) {
            return selectedGuid
        }
        val firstEligible = profilesInOrder.firstOrNull { isCdnScanEligible(it.second) }?.first
        if (firstEligible != null) return firstEligible
        if (!selectedGuid.isNullOrBlank() && map.containsKey(selectedGuid)) return selectedGuid
        return profilesInOrder.firstOrNull()?.first
    }
}
