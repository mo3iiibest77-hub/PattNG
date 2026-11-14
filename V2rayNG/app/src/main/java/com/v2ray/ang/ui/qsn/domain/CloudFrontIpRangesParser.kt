package com.v2ray.ang.ui.qsn.domain

import com.google.gson.JsonParser

/**
 * Pure parser for the official AWS IP ranges JSON (`https://ip-ranges.amazonaws.com/ip-ranges.json`).
 *
 * Extracts only IPv4 `ip_prefix` entries belonging to `service == "CLOUDFRONT"`,
 * normalizes to network CIDR notation, and de-duplicates while keeping insertion order.
 * No invented/guessed CIDRs are ever returned.
 */
object CloudFrontIpRangesParser {

    const val OFFICIAL_IP_RANGES_URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"
    private const val SERVICE_CLOUDFRONT = "CLOUDFRONT"

    fun parseIpv4Cidrs(jsonText: String): List<String> {
        if (jsonText.isBlank()) return emptyList()
        val root = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val prefixes = root.getAsJsonArray("prefixes") ?: return emptyList()
        val result = LinkedHashSet<String>()
        for (elem in prefixes) {
            if (!elem.isJsonObject) continue
            val obj = elem.asJsonObject
            val service = obj.get("service")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
            if (!service.equals(SERVICE_CLOUDFRONT, ignoreCase = true)) continue
            val rawPrefix = obj.get("ip_prefix")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val normalized = ProviderRangeBucket.normalizeCidrOrNull(rawPrefix) ?: continue
            result.add(normalized)
        }
        return result.toList()
    }
}
