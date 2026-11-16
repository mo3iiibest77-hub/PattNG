package com.v2ray.ang.senpai

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

/**
 * Execution and UI pipeline for a proxy profile.
 *
 * [CDN]: Cloudflare / CloudFront fronted nodes that use CDN IP scanning, TLS-SNI tuning,
 * and NovaPulse's CDN hardening rules without behavioral changes.
 *
 * [STANDARD]: Regular v2rayNG-compatible direct/encrypted protocols (XHTTP REALITY, TCP REALITY,
 * gRPC REALITY, Hysteria2, VLESS TLS/none, VMess, Trojan, Shadowsocks, WireGuard, Socks, HTTP)
 * that bypass CDN scanners and CDN TLS mutations completely.
 */
enum class ConfigPipeline {
    CDN,
    STANDARD,
}

enum class ConfigListBucket {
    ALL,
    CDN,
    STANDARD,
}

object ConfigPipelineClassifier {

    private val SUPPORTED_STANDARD_TYPES = setOf(
        EConfigType.VLESS,
        EConfigType.VMESS,
        EConfigType.TROJAN,
        EConfigType.SHADOWSOCKS,
        EConfigType.HYSTERIA2,
        EConfigType.WIREGUARD,
        EConfigType.SOCKS,
        EConfigType.HTTP,
    )

    private val SUPPORTED_MANUAL_SCHEMES = listOf(
        "vless://",
        "vmess://",
        "trojan://",
        "ss://",
        "hysteria2://",
        "hy2://",
        "wireguard://",
        "socks://",
    )

    fun classify(profile: ProfileItem?): ConfigPipeline {
        return if (AutoConfigFilter.isCdnScanEligible(profile)) {
            ConfigPipeline.CDN
        } else {
            ConfigPipeline.STANDARD
        }
    }

    fun isCdn(profile: ProfileItem?): Boolean = AutoConfigFilter.isCdnScanEligible(profile)

    fun isCloudflareCdn(profile: ProfileItem?): Boolean {
        return AutoConfigFilter.isCdnScanEligible(profile) &&
            !CloudflareConfigEnricher.isAmazonOrCloudFront(profile)
    }

    fun isStandardEligible(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        if (AutoConfigFilter.isCdnScanEligible(profile)) return false
        if (profile.configType !in SUPPORTED_STANDARD_TYPES) return false
        if (profile.server.isNullOrBlank()) return false
        val port = profile.serverPort?.trim()?.toIntOrNull() ?: return false
        if (port !in 1..65535) return false

        return when (profile.configType) {
            EConfigType.VLESS,
            EConfigType.VMESS,
            EConfigType.TROJAN,
            EConfigType.HYSTERIA2 -> !profile.password.isNullOrBlank()

            EConfigType.SHADOWSOCKS ->
                !profile.password.isNullOrBlank() && !profile.method.isNullOrBlank()

            EConfigType.WIREGUARD -> !profile.secretKey.isNullOrBlank()

            EConfigType.SOCKS,
            EConfigType.HTTP -> true

            else -> false
        }
    }

    fun isSupportedImport(profile: ProfileItem?): Boolean {
        return isCdn(profile) || isStandardEligible(profile)
    }

    fun isManualConfig(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        val subId = profile.subscriptionId.trim()
        return subId.isEmpty() || subId == AppConfig.DEFAULT_SUBSCRIPTION_ID
    }

    fun safeTransportBadge(profile: ProfileItem): String {
        if (isCdn(profile)) {
            val net = (profile.network ?: "xhttp").uppercase()
            return if (CloudflareConfigEnricher.isAmazonOrCloudFront(profile)) {
                "CloudFront • $net"
            } else {
                "CDN • $net"
            }
        }
        if (profile.configType == EConfigType.HYSTERIA2) {
            return "Hysteria2 • QUIC"
        }
        val net = (profile.network ?: "tcp").uppercase()
        if (profile.security.equals(AppConfig.REALITY, ignoreCase = true)) {
            return "REALITY • $net"
        }
        return "${profile.configType.name} • $net"
    }

    fun extractManualImportPayload(rawInput: String?): String? {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val directLines = filterManualProxyLines(trimmed.lines())
        if (directLines.isNotEmpty()) {
            return directLines.joinToString("\n")
        }

        val decoded = runCatching { Utils.decode(trimmed) }.getOrDefault("")
        if (decoded.isBlank()) return null
        val decodedLines = filterManualProxyLines(decoded.lines())
        return decodedLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun filterManualProxyLines(lines: List<String>): List<String> {
        return lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val lower = line.lowercase()
            if (SUPPORTED_MANUAL_SCHEMES.any { lower.startsWith(it) }) line else null
        }
    }

    fun selectBestStandardGuid(
        profilesInOrder: List<Pair<String, ProfileItem>>,
        delaysByGuid: Map<String, Long> = emptyMap(),
        excludeGuid: String? = null,
    ): String? {
        val candidates = profilesInOrder.filter { (guid, profile) ->
            guid != excludeGuid && isStandardEligible(profile)
        }
        if (candidates.isEmpty()) return null

        val reachable = candidates.mapNotNull { (guid, _) ->
            val delay = delaysByGuid[guid] ?: 0L
            if (delay > 0L) guid to delay else null
        }
        if (reachable.isNotEmpty()) {
            return reachable.minByOrNull { it.second }?.first
        }

        return candidates.minByOrNull { (_, profile) -> protocolPriorityRank(profile) }?.first
    }

    fun protocolPriorityRank(profile: ProfileItem): Int {
        return when {
            profile.security.equals(AppConfig.REALITY, ignoreCase = true) -> 0
            profile.configType == EConfigType.HYSTERIA2 -> 1
            profile.configType == EConfigType.VLESS ||
                profile.configType == EConfigType.TROJAN ||
                profile.configType == EConfigType.VMESS -> 2
            else -> 3
        }
    }
}
