package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Identifies CDN-tunneled proxy configs suitable for Cloudflare/CloudFront IP optimization
 * while leaving all other configs untouched and viewable in manual mode.
 */
object AutoConfigFilter {

    private val SUPPORTED_TRANSPORTS = setOf("xhttp", "ws", "splithttp")

    fun isCdnScanEligible(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        if (profile.configType != EConfigType.VLESS) return false
        val security = profile.security?.trim()?.lowercase()
        if (security != "tls") return false
        val network = profile.network?.trim()?.lowercase() ?: return false
        return network in SUPPORTED_TRANSPORTS
    }

    /**
     * Picks the preferred configuration GUID from candidate profiles:
     * 1) Currently selected GUID if it is CDN-eligible
     * 2) First CDN-eligible GUID in the active subscription/list
     * 3) Currently selected GUID (fallback for non-CDN configs)
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
