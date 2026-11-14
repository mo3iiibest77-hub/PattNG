package com.v2ray.ang.senpai

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Parses and fetches official Amazon CloudFront IPv4 CIDR ranges from
 * `https://ip-ranges.amazonaws.com/ip-ranges.json`.
 */
object CloudFrontIpRangesParser {

    const val AWS_IP_RANGES_URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"

    /**
     * Extracts distinct sorted IPv4 CIDRs where `service == "CLOUDFRONT"` from AWS `ip-ranges.json`.
     */
    fun parseAwsIpRangesJson(jsonText: String): List<String> {
        if (jsonText.isBlank()) return emptyList()
        val root = JsonParser.parseString(jsonText).asJsonObject
        val prefixes = root.getAsJsonArray("prefixes") ?: return emptyList()
        val cidrs = linkedSetOf<String>()
        for (element in prefixes) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val service = obj.get("service")?.asString ?: continue
            if (!service.equals("CLOUDFRONT", ignoreCase = true)) continue
            val rawPrefix = obj.get("ip_prefix")?.asString ?: continue
            CloudflareSeedRanges.normalizeIpv4Cidr(rawPrefix)?.let { cidrs.add(it) }
        }
        return cidrs.sorted()
    }

    /**
     * Parses newline-delimited CIDR asset text while ignoring comments and blank lines.
     */
    fun parseCidrLines(text: String): List<String> {
        val result = linkedSetOf<String>()
        for (line in text.lineSequence()) {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) continue
            CloudflareSeedRanges.normalizeIpv4Cidr(clean)?.let { result.add(it) }
        }
        return result.toList()
    }

    /**
     * Downloads the latest official AWS CloudFront IPv4 prefixes directly over the network.
     * Callers inside an active VPN session invoke this from the excluded app process (or bound socket).
     */
    fun fetchLatestCloudFrontCidrs(
        client: OkHttpClient = defaultClient(),
        url: String = AWS_IP_RANGES_URL,
    ): Result<List<String>> = runCatching {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("AWS ip-ranges HTTP ${resp.code}")
            }
            val body = resp.body.string()
            val cidrs = parseAwsIpRangesJson(body)
            check(cidrs.isNotEmpty()) { "Empty CLOUDFRONT prefix list from AWS ip-ranges.json" }
            cidrs
        }
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
}
