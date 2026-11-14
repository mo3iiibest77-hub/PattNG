package com.v2ray.ang.senpai

import android.content.Context
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Classifies a configuration's origin domain into [CdnType.CLOUDFLARE],
 * [CdnType.CLOUDFRONT], or [CdnType.UNKNOWN] using unpoisoned DoH resolution,
 * physical DNS + bounded ICMP ping fallback, full Cloudflare ASN & AWS CloudFront CIDR matching,
 * and HTTPS edge header probing.
 */
object CdnPingClassifier {

    private val IPV4_IN_PARENS: Pattern = Pattern.compile("""\((\d{1,3}(?:\.\d{1,3}){3})\)""")
    private val PING_TIME_MS: Pattern = Pattern.compile("""time[=<](\d+(?:\.\d+)?)\s*ms""", Pattern.CASE_INSENSITIVE)

    @Volatile
    private var cachedDiscoveryCfCidrs: List<String>? = null

    /**
     * Filters out private, loopback, link-local, CGNAT, and Iranian DNS-poisoning sinkhole IPs
     * (such as `10.10.34.*`) so poisoned local resolver responses never misclassify domains.
     */
    fun isPublicRoutableIpv4(ip: String): Boolean {
        val v = CloudflareSeedRanges.ipv4ToLong(ip) ?: return false
        val b0 = ((v ushr 24) and 0xFF).toInt()
        val b1 = ((v ushr 16) and 0xFF).toInt()
        return when {
            b0 == 0 -> false
            b0 == 10 -> false // RFC1918 + ISP DNS filtering sinkholes (10.10.34.x)
            b0 == 127 -> false // Loopback
            b0 == 100 && b1 in 64..127 -> false // CGNAT
            b0 == 169 && b1 == 254 -> false // Link-local
            b0 == 172 && b1 in 16..31 -> false // RFC1918
            b0 == 192 && b1 == 168 -> false // RFC1918
            b0 >= 224 -> false // Multicast/reserved
            else -> true
        }
    }

    /**
     * Extracts valid public IPv4 Type-A (type == 1) answers from Cloudflare/Google DoH JSON.
     */
    fun parseDohJsonIpv4s(json: String): List<String> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val answers = root.getAsJsonArray("Answer") ?: return@runCatching emptyList()
        buildList {
            for (el in answers) {
                val obj = el.asJsonObject
                val type = obj.get("type")?.asInt ?: continue
                if (type != 1) continue
                val data = obj.get("data")?.asString?.trim() ?: continue
                if (isPublicRoutableIpv4(data)) add(data)
            }
        }.distinct()
    }.getOrDefault(emptyList())

    /**
     * Pure JVM-testable classifier mapping a resolved IPv4 address against known CloudFront
     * and Cloudflare CIDR lists.
     */
    fun classifyResolvedIp(
        domain: String,
        resolvedIp: String?,
        pingRttMs: Long?,
        cloudFrontCidrs: Iterable<String>,
        cloudflareCidrs: Iterable<String> =
            CloudflareSeedRanges.SEED_CIDRS + CloudflareSeedRanges.OFFICIAL_CLASSIFICATION_CIDRS,
    ): CdnDetectionResult {
        val cleanIp = resolvedIp?.trim()?.takeIf { CloudflareSeedRanges.ipv4ToLong(it) != null }
        if (cleanIp == null) {
            return CdnDetectionResult(
                domain = domain,
                resolvedIp = null,
                pingRttMs = pingRttMs,
                cdnType = CdnType.UNKNOWN,
                reason = "DNS resolution returned no IPv4 address"
            )
        }
        if (CloudflareSeedRanges.matchesAnyCidr(cleanIp, cloudFrontCidrs)) {
            return CdnDetectionResult(
                domain = domain,
                resolvedIp = cleanIp,
                pingRttMs = pingRttMs,
                cdnType = CdnType.CLOUDFRONT,
                reason = "Matched AWS CloudFront prefix"
            )
        }
        if (CloudflareSeedRanges.matchesAnyCidr(cleanIp, cloudflareCidrs)) {
            return CdnDetectionResult(
                domain = domain,
                resolvedIp = cleanIp,
                pingRttMs = pingRttMs,
                cdnType = CdnType.CLOUDFLARE,
                reason = "Matched Cloudflare prefix"
            )
        }
        return CdnDetectionResult(
            domain = domain,
            resolvedIp = cleanIp,
            pingRttMs = pingRttMs,
            cdnType = CdnType.UNKNOWN,
            reason = "IP $cleanIp outside known Cloudflare/CloudFront ranges"
        )
    }

    /**
     * Loads the complete bundled Cloudflare classification set (all 600+ ASN CIDRs + 46 seeds +
     * official blocks) so domains hosted on any Cloudflare IP range are matched reliably.
     */
    fun getAllCloudflareCidrs(context: Context): List<String> {
        val discovery = cachedDiscoveryCfCidrs ?: runCatching {
            context.assets.open("cf_ranges_discovery_v4.txt")
                .bufferedReader()
                .use { reader ->
                    reader.readLines()
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .mapNotNull { CloudflareSeedRanges.normalizeIpv4Cidr(it) }
                        .toList()
                }
        }.getOrDefault(emptyList()).also {
            if (it.isNotEmpty()) cachedDiscoveryCfCidrs = it
        }
        return (
            ProviderRangeStore.getEntries(context, CdnType.CLOUDFLARE).map { it.cidr } +
                CloudflareSeedRanges.SEED_CIDRS +
                CloudflareSeedRanges.OFFICIAL_CLASSIFICATION_CIDRS +
                discovery
            ).distinct()
    }

    /**
     * Parses the first IPv4 and RTT (rounded ms) from standard Android/Linux `ping` stdout.
     */
    fun parsePingOutput(stdout: String): Pair<String?, Long?> {
        val ipMatcher = IPV4_IN_PARENS.matcher(stdout)
        val ip = if (ipMatcher.find()) ipMatcher.group(1) else null
        val rttMatcher = PING_TIME_MS.matcher(stdout)
        val rtt = if (rttMatcher.find()) {
            rttMatcher.group(1)?.toDoubleOrNull()?.toLong()
        } else {
            null
        }
        return ip to rtt
    }

    fun extractOrderedHostCandidates(
        profile: ProfileItem,
        savedOrigin: String? = null,
    ): List<String> {
        val raw = listOfNotNull(
            profile.host,
            profile.sni,
            savedOrigin,
            profile.server,
        ).mapNotNull { entry ->
            entry.trim().substringBefore(',').trim().takeIf { it.isNotEmpty() }
        }.distinct()

        val domains = raw.filter { CloudflareSeedRanges.ipv4ToLong(it) == null }
        val ips = raw.filter { CloudflareSeedRanges.ipv4ToLong(it) != null }
        return domains + ips
    }

    /**
     * Persists verified CDN type per profile GUID so CloudFront/Cloudflare routing stays exact
     * across app restarts without losing original classification when `profile.server` becomes an IP.
     */
    object CdnDetectionStore {
        private const val KEY_PREFIX = "qsn_cdn_detected_v1_"

        fun save(guid: String, cdnType: CdnType) {
            if (guid.isBlank() || cdnType == CdnType.UNKNOWN) return
            MmkvManager.encodeSettings(KEY_PREFIX + guid, cdnType.name)
        }

        fun getSavedCdnType(guid: String): CdnType? {
            if (guid.isBlank()) return null
            val raw = MmkvManager.decodeSettingsString(KEY_PREFIX + guid) ?: return null
            return runCatching { CdnType.valueOf(raw) }.getOrNull()?.takeIf { it != CdnType.UNKNOWN }
        }

        fun clear(guid: String) {
            if (guid.isBlank()) return
            MmkvManager.encodeSettings(KEY_PREFIX + guid, "")
        }

        fun getAllSaved(guids: Collection<String>): Map<String, CdnType> = buildMap {
            for (g in guids) {
                getSavedCdnType(g)?.let { put(g, it) }
            }
        }
    }

    /**
     * Fast local hint classification using WS Host, TLS SNI, origin domain, current server IP,
     * and full Cloudflare/CloudFront CIDR lists without blocking UI thread on network calls.
     */
    fun inferQuickCdnType(
        profile: ProfileItem,
        originDomain: String,
        frontCidrs: List<String>,
        cfCidrs: List<String>,
        cachedType: CdnType? = null,
    ): CdnType {
        if (cachedType != null && cachedType != CdnType.UNKNOWN) {
            return cachedType
        }
        val candidates = listOfNotNull(
            profile.host?.trim()?.takeIf { it.isNotEmpty() },
            profile.sni?.trim()?.takeIf { it.isNotEmpty() },
            originDomain.trim().takeIf { it.isNotEmpty() },
            profile.server?.trim()?.takeIf { it.isNotEmpty() },
        )
        for (ip in candidates) {
            if (CloudflareSeedRanges.ipv4ToLong(ip) != null && isPublicRoutableIpv4(ip)) {
                if (CloudflareSeedRanges.matchesAnyCidr(ip, frontCidrs)) return CdnType.CLOUDFRONT
                if (CloudflareSeedRanges.matchesAnyCidr(ip, cfCidrs)) return CdnType.CLOUDFLARE
            }
        }
        val hosts = candidates.map { it.lowercase() }
        if (hosts.any { "cloudfront.net" in it || "amazonaws.com" in it }) return CdnType.CLOUDFRONT
        return CdnType.UNKNOWN
    }

    /**
     * Classifies [profile] over the physical network using ordered candidates
     * (**WS Host -> TLS SNI -> Saved Origin -> Server Address**):
     * 1) Bounded ICMP `ping -c 1 -W 2 <host>` (inspecting the returned ICMP IP against CloudFront/Cloudflare CIDRs!)
     * 2) DoH JSON lookup (dns.google & 1.1.1.1) bypassing ISP DNS poisoning
     * 3) Physical-network DNS `getAllByName` filtered to public routable IPv4s
     * 4) HTTPS edge header probe (`CF-RAY`, `Server: cloudflare`, `Via: CloudFront`, `X-Amz-Cf-Id`)
     */
    fun detectForProfile(
        context: Context,
        guid: String,
        profile: ProfileItem,
    ): CdnDetectionResult {
        OriginDomainStore.rememberIfDomain(guid, profile)
        val savedOrigin = OriginDomainStore.getSavedOriginDomain(guid)
        val candidates = extractOrderedHostCandidates(profile, savedOrigin)
        if (candidates.isEmpty()) {
            return CdnDetectionResult("", null, null, CdnType.UNKNOWN, "No WS Host, SNI, or server address present")
        }

        val cfCidrs = getAllCloudflareCidrs(context)
        val frontCidrs = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT)
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }

        val physicalNetwork = PhysicalNetworkGuard.findActivePhysicalNetwork(context)
        var lastPingRtt: Long? = null
        var firstResolvedIp: String? = null

        // First check explicit CloudFront domain markers in WS Host / SNI / origin
        for (host in candidates) {
            if ("cloudfront.net" in host.lowercase() || "amazonaws.com" in host.lowercase()) {
                val res = CdnDetectionResult(host, null, null, CdnType.CLOUDFRONT, "Matched CloudFront origin hostname")
                CdnDetectionStore.save(guid, CdnType.CLOUDFRONT)
                return res
            }
        }

        for (targetHost in candidates) {
            if (CloudflareSeedRanges.ipv4ToLong(targetHost) != null) {
                if (!isPublicRoutableIpv4(targetHost)) continue
                val classified = classifyResolvedIp(targetHost, targetHost, null, frontCidrs, cfCidrs)
                if (classified.cdnType != CdnType.UNKNOWN) {
                    CdnDetectionStore.save(guid, classified.cdnType)
                    return classified
                }
                continue
            }

            // 1. Bounded ICMP ping to get exact edge IP returned for WS Host / SNI
            val (pingIpRaw, pingRtt) = runBoundedPing(targetHost)
            if (pingRtt != null) lastPingRtt = pingRtt
            val pingIp = pingIpRaw?.takeIf { isPublicRoutableIpv4(it) }

            // 2. Unpoisoned DoH + physical DNS fallback
            val dohIps = resolveViaDoh(targetHost, context)
            val localDnsIps = resolveAllIpv4(targetHost, physicalNetwork).filter { isPublicRoutableIpv4(it) }

            val candidateIps = buildList {
                if (pingIp != null) add(pingIp)
                addAll(dohIps)
                addAll(localDnsIps)
            }.distinct()
            if (firstResolvedIp == null && candidateIps.isNotEmpty()) {
                firstResolvedIp = candidateIps.first()
            }

            for (ip in candidateIps) {
                val classified = classifyResolvedIp(targetHost, ip, pingRtt, frontCidrs, cfCidrs)
                if (classified.cdnType != CdnType.UNKNOWN) {
                    CdnDetectionStore.save(guid, classified.cdnType)
                    return classified
                }
            }

            // 3. HTTPS header fallback on this host
            val headerCdn = probeHttpHeadersForCdn(targetHost, context)
            if (headerCdn != CdnType.UNKNOWN) {
                val res = CdnDetectionResult(
                    domain = targetHost,
                    resolvedIp = candidateIps.firstOrNull(),
                    pingRttMs = pingRtt,
                    cdnType = headerCdn,
                    reason = "Detected via HTTPS edge headers on $targetHost"
                )
                CdnDetectionStore.save(guid, headerCdn)
                return res
            }
        }

        val fallback = CdnDetectionResult(
            domain = candidates.first(),
            resolvedIp = firstResolvedIp,
            pingRttMs = lastPingRtt,
            cdnType = CdnType.UNKNOWN,
            reason = "Unable to map ${firstResolvedIp ?: candidates.first()} to Cloudflare or CloudFront"
        )
        return fallback
    }

    /**
     * Classifies any stored eligible CDN profiles that lack a cached [CdnDetectionStore] result
     * (or all eligible profiles when [forceRefresh] is true) using WS Host / SNI + ICMP ping + DoH.
     */
    fun classifyAllProfilesIfNeeded(context: Context, forceRefresh: Boolean = false): Int {
        val guids = MmkvManager.decodeAllServerList()
        var updated = 0
        for (guid in guids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!AutoConfigFilter.isCdnScanEligible(profile)) continue
            if (!forceRefresh && CdnDetectionStore.getSavedCdnType(guid) != null) continue
            val res = runCatching { detectForProfile(context, guid, profile) }.getOrNull() ?: continue
            if (res.cdnType != CdnType.UNKNOWN) {
                updated++
            }
        }
        return updated
    }

    fun resolveViaDoh(domain: String, context: Context? = null): List<String> {
        if (!domain.matches(Regex("""^[A-Za-z0-9.\-]+$"""))) return emptyList()
        val encoded = URLEncoder.encode(domain, "UTF-8")
        val client = (context?.let {
            PhysicalNetworkGuard.createPhysicalOkHttpClient(it, connectTimeoutSec = 3L, readTimeoutSec = 3L)
        } ?: OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build())
        val endpoints = listOf(
            "https://dns.google/resolve?name=$encoded&type=A",
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=A",
        )
        for (url in endpoints) {
            val ips = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else parseDohJsonIpv4s(resp.body.string())
                }
            }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private fun runBoundedPing(domain: String): Pair<String?, Long?> {
        if (!domain.matches(Regex("""^[A-Za-z0-9.\-]+$"""))) return null to null
        return runCatching {
            val process = ProcessBuilder("ping", "-c", "1", "-W", "2", domain)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching null to null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            parsePingOutput(output)
        }.getOrDefault(null to null)
    }

    private fun resolveAllIpv4(domain: String, physicalNetwork: android.net.Network? = null): List<String> = runCatching {
        val addrs = if (physicalNetwork != null) {
            physicalNetwork.getAllByName(domain)
        } else {
            InetAddress.getAllByName(domain)
        }
        addrs.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun probeHttpHeadersForCdn(domain: String, context: Context? = null): CdnType {
        val baseClient = context?.let {
            PhysicalNetworkGuard.createPhysicalOkHttpClient(it, connectTimeoutSec = 4L, readTimeoutSec = 4L)
        } ?: OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        val client = baseClient.newBuilder()
            .followRedirects(false)
            .build()
        return runCatching {
            val request = Request.Builder().url("https://$domain/").head().build()
            client.newCall(request).execute().use { resp ->
                val server = resp.header("Server").orEmpty().lowercase()
                val via = resp.header("Via").orEmpty().lowercase()
                val cfRay = resp.header("CF-RAY")
                when {
                    !cfRay.isNullOrBlank() || "cloudflare" in server -> CdnType.CLOUDFLARE
                    "cloudfront" in server || "cloudfront" in via || !resp.header("X-Amz-Cf-Id").isNullOrBlank() ->
                        CdnType.CLOUDFRONT
                    else -> CdnType.UNKNOWN
                }
            }
        }.getOrDefault(CdnType.UNKNOWN)
    }
}
