package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Classifies a configuration's origin domain into [CdnType.CLOUDFLARE],
 * [CdnType.CLOUDFRONT], or [CdnType.UNKNOWN] via physical DNS resolution,
 * bounded ICMP ping, and CIDR/header matching.
 */
object CdnPingClassifier {

    private val IPV4_IN_PARENS: Pattern = Pattern.compile("""\((\d{1,3}(?:\.\d{1,3}){3})\)""")
    private val PING_TIME_MS: Pattern = Pattern.compile("""time[=<](\d+(?:\.\d+)?)\s*ms""", Pattern.CASE_INSENSITIVE)

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

    /**
     * Classifies [profile] over the physical network using:
     * 1) `ping -c 1 -W 2 <domain>` (simultaneously capturing resolved IP and ICMP RTT),
     * 2) [InetAddress.getAllByName] fallback when ICMP is filtered or blocked,
     * 3) HTTP `Server`/`Via`/`CF-RAY` response-header tie-breaker if the IP CIDR wasn't listed.
     */
    fun detectForProfile(
        context: Context,
        guid: String,
        profile: ProfileItem,
    ): CdnDetectionResult {
        OriginDomainStore.rememberIfDomain(guid, profile)
        val domain = OriginDomainStore.getOriginDomain(guid, profile)
            ?: return CdnDetectionResult("", null, null, CdnType.UNKNOWN, "No domain present")

        val cfCidrs = (
            ProviderRangeStore.getEntries(context, CdnType.CLOUDFLARE).map { it.cidr } +
                CloudflareSeedRanges.SEED_CIDRS +
                CloudflareSeedRanges.OFFICIAL_CLASSIFICATION_CIDRS
            ).distinct()
        val frontCidrs = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT)
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }

        // If the profile has only a literal IPv4 server and no domain hostname, classify directly.
        if (CloudflareSeedRanges.ipv4ToLong(domain) != null) {
            return classifyResolvedIp(domain, domain, null, frontCidrs, cfCidrs)
        }

        val (pingIp, pingRtt) = runBoundedPing(domain)
        val dnsIps = resolveAllIpv4(domain)
        val candidateIps = buildList {
            if (pingIp != null) add(pingIp)
            addAll(dnsIps)
        }.distinct()

        for (ip in candidateIps) {
            val classified = classifyResolvedIp(domain, ip, pingRtt, frontCidrs, cfCidrs)
            if (classified.cdnType != CdnType.UNKNOWN) {
                return classified
            }
        }

        val headerCdn = probeHttpHeadersForCdn(domain)
        if (headerCdn != CdnType.UNKNOWN) {
            return CdnDetectionResult(
                domain = domain,
                resolvedIp = candidateIps.firstOrNull(),
                pingRttMs = pingRtt,
                cdnType = headerCdn,
                reason = "Detected via HTTP edge headers"
            )
        }

        return CdnDetectionResult(
            domain = domain,
            resolvedIp = candidateIps.firstOrNull(),
            pingRttMs = pingRtt,
            cdnType = CdnType.UNKNOWN,
            reason = "Unable to map ${candidateIps.firstOrNull() ?: domain} to Cloudflare or CloudFront"
        )
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

    private fun resolveAllIpv4(domain: String): List<String> = runCatching {
        InetAddress.getAllByName(domain)
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun probeHttpHeadersForCdn(domain: String): CdnType {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
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
