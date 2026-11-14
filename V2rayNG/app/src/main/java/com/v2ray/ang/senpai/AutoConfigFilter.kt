package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Enforces NovaPulse CDN-only configuration policy and deterministic Cloudflare-first priority:
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
     * Picks the preferred configuration GUID from candidate profiles in subscription order:
     * - When [honorExplicitSelection] is true and [selectedGuid] is CDN-eligible, uses it directly
     *   (e.g. user manually picked a specific server row in Configs tab).
     * - Otherwise prioritizes **Cloudflare** over Amazon/CloudFront:
     *   1) [selectedGuid] if it is Cloudflare + CDN-eligible
     *   2) First Cloudflare CDN-eligible GUID in subscription order (first-to-last)
     *   3) [selectedGuid] if CDN-eligible (only when no Cloudflare config exists)
     *   4) First CDN-eligible GUID
     *   5) Fallback to [selectedGuid] or first available
     */
    fun pickBestConnectGuid(
        selectedGuid: String?,
        profilesInOrder: List<Pair<String, ProfileItem?>>,
        honorExplicitSelection: Boolean = false,
        cloudFrontCidrs: Collection<String> = emptyList(),
    ): String? {
        val map = profilesInOrder.toMap()
        if (honorExplicitSelection && !selectedGuid.isNullOrBlank() && isCdnScanEligible(map[selectedGuid])) {
            return selectedGuid
        }

        val isCf: (ProfileItem?) -> Boolean = { p ->
            CloudflareConfigEnricher.isCloudflareConfig(
                profile = p,
                originDomain = null,
                cloudFrontCidrs = cloudFrontCidrs,
            )
        }

        if (!selectedGuid.isNullOrBlank() && isCf(map[selectedGuid])) {
            return selectedGuid
        }
        val firstCloudflare = profilesInOrder.firstOrNull { isCf(it.second) }?.first
        if (firstCloudflare != null) return firstCloudflare

        if (!selectedGuid.isNullOrBlank() && isCdnScanEligible(map[selectedGuid])) {
            return selectedGuid
        }
        val firstEligible = profilesInOrder.firstOrNull { isCdnScanEligible(it.second) }?.first
        if (firstEligible != null) return firstEligible

        if (!selectedGuid.isNullOrBlank() && map.containsKey(selectedGuid)) return selectedGuid
        return profilesInOrder.firstOrNull()?.first
    }
}
