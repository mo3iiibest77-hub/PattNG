package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Classifies Cloudflare vs Amazon/CloudFront configurations and applies Cloudflare-only
 * TLS hardening (`cipherSuites`, `finalMask` / TCB Mask, and `"unsafe"` fingerprint) immediately
 * on import/license activation while keeping Amazon/CloudFront configs untouched.
 *
 * Also handles SNI extraction from TLS settings for fast first-connect without awaiting IP scan.
 */
object CloudflareConfigEnricher {

    private const val TAG = "CloudflareEnricher"
    private val SEPARATOR_REGEX = Regex("""[\s\-_─│·•~╯╰（）()\[\]🤙☁️🇸🇪👱🏻🚨]+""")
    private val DOMAIN_NAME_REGEX = Regex(
        """^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$"""
    )

    private val OFFICIAL_AND_SEED_CF_CIDRS: List<String> by lazy {
        (CloudflareSeedRanges.SEED_CIDRS + CloudflareSeedRanges.OFFICIAL_CLASSIFICATION_CIDRS).distinct()
    }

    /**
     * Normalizes colon-separated cipher suites, preserving order and preventing duplicates.
     */
    fun normalizeCipherSuites(raw: String?): String {
        val parts = raw.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return parts.joinToString(":")
    }

    /**
     * Detects Amazon / AWS / CloudFront configs using:
     * 1) Domain / SNI / Host indicators (`cloudfront.net`, `amazonaws.com`)
     * 2) Remark indicators (`amazon`, `cloudfront`, `aws` even with decorative box-drawing chars like `╰a─m─a─z─o─n╯`)
     * 3) IPv4 CIDR match against known CloudFront prefixes
     */
    fun isAmazonOrCloudFront(
        profile: ProfileItem?,
        originDomain: String? = null,
        cloudFrontCidrs: Collection<String> = emptyList(),
    ): Boolean {
        if (profile == null) return false

        val hosts = listOfNotNull(
            profile.sni,
            profile.host,
            profile.server,
            originDomain,
        ).map { it.trim().lowercase() }

        if (hosts.any { "cloudfront.net" in it || "amazonaws.com" in it }) {
            return true
        }

        val cleanedRemark = SEPARATOR_REGEX.replace(profile.remarks.lowercase(), "")
        if ("amazon" in cleanedRemark || "cloudfront" in cleanedRemark) {
            return true
        }

        if (cloudFrontCidrs.isNotEmpty()) {
            val ips = listOfNotNull(profile.server, originDomain)
                .map { it.trim() }
                .filter { CloudflareSeedRanges.ipv4ToLong(it) != null }
            if (ips.any { CloudflareSeedRanges.matchesAnyCidr(it, cloudFrontCidrs) }) {
                return true
            }
        }
        return false
    }

    /**
     * Determines whether [profile] should be treated as a Cloudflare CDN configuration
     * eligible for Cloudflare-first priority, immediate CipherSuites + FinalMask injection,
     * and Cloudflare IP scanning.
     */
    fun isCloudflareConfig(
        profile: ProfileItem?,
        originDomain: String? = null,
        cloudflareCidrs: Collection<String> = OFFICIAL_AND_SEED_CF_CIDRS,
        cloudFrontCidrs: Collection<String> = emptyList(),
    ): Boolean {
        if (profile == null || !AutoConfigFilter.isCdnScanEligible(profile)) return false
        if (isAmazonOrCloudFront(profile, originDomain, cloudFrontCidrs)) return false

        val serverTrimmed = profile.server?.trim().orEmpty()
        if (CloudflareSeedRanges.ipv4ToLong(serverTrimmed) != null &&
            CdnPingClassifier.isPublicRoutableIpv4(serverTrimmed)
        ) {
            return CloudflareSeedRanges.matchesAnyCidr(serverTrimmed, cloudflareCidrs)
        }
        return true
    }

    /**
     * Idempotently applies hardcoded Cloudflare `cipherSuites`, `finalMask` (TCB Mask), and
     * `"unsafe"` fingerprint to [profile] ONLY when it is a Cloudflare config.
     * Returns `true` if [profile] is Cloudflare and valid (regardless of whether fields changed),
     * or `false` if [profile] is Amazon/CloudFront or ineligible.
     */
    fun applyCloudflareHardeningIfEligible(
        profile: ProfileItem,
        originDomain: String? = null,
        cloudflareCidrs: Collection<String> = OFFICIAL_AND_SEED_CF_CIDRS,
        cloudFrontCidrs: Collection<String> = emptyList(),
    ): Boolean {
        if (!isCloudflareConfig(profile, originDomain, cloudflareCidrs, cloudFrontCidrs)) {
            return false
        }

        val canonicalSuites = normalizeCipherSuites(CloudflareScanner.PRODUCTION_CIPHERSUITES)
        val currentSuites = normalizeCipherSuites(profile.cipherSuites)
        if (currentSuites != canonicalSuites) {
            val merged = (currentSuites.split(':').filter { it.isNotEmpty() } + canonicalSuites.split(':'))
                .distinct()
                .joinToString(":")
            profile.cipherSuites = merged
        }

        if (profile.finalMask.isNullOrBlank()) {
            profile.finalMask = CloudflareScanner.PRODUCTION_FINALMASK
        } else {
            val parsedMask = JsonUtil.parseString(profile.finalMask)
            if (parsedMask == null) {
                profile.finalMask = CloudflareScanner.PRODUCTION_FINALMASK
            }
        }

        profile.fingerPrint = "unsafe"
        return true
    }

    /**
     * Extracts the TLS Certification SNI (`profile.sni`) for immediate first-connect without
     * confusing `host` / `xhttpHost` with TLS SNI. Returns `null` if no valid domain SNI exists.
     */
    fun extractTlsSniForInitialConnect(profile: ProfileItem?): String? {
        if (profile == null || !AutoConfigFilter.isCdnScanEligible(profile)) return null
        val tlsSni = profile.sni?.trim()?.substringBefore(',')?.trim().orEmpty()
        if (tlsSni.isEmpty()) return null
        if (Utils.isPureIpAddress(tlsSni)) return null
        if (!DOMAIN_NAME_REGEX.matches(tlsSni)) return null
        return tlsSni
    }

    /**
     * Prepares [profile] for instant first-connect by placing its TLS Certification SNI into
     * `profile.server` while keeping all TLS SNI, Host, XHTTP, and CipherSuites fields intact.
     */
    fun prepareFirstConnectWithTlsSni(
        guid: String,
        profile: ProfileItem,
        cloudFrontCidrs: Collection<String> = emptyList(),
    ): ProfileItem? {
        OriginDomainStore.rememberIfDomain(guid, profile)
        if (isCloudflareConfig(profile, OriginDomainStore.getOriginDomain(guid, profile), cloudFrontCidrs = cloudFrontCidrs)) {
            applyCloudflareHardeningIfEligible(profile, cloudFrontCidrs = cloudFrontCidrs)
        }
        val tlsSni = extractTlsSniForInitialConnect(profile)
        if (tlsSni != null) {
            profile.server = tlsSni
        } else if (profile.server.isNullOrBlank()) {
            LogUtil.e(TAG, "Cannot prepare initial connect for $guid (${profile.remarks}): missing TLS SNI and server address")
            return null
        }

        return validateAndPersistProfile(guid, profile)
    }

    /**
     * Enriches and validates every stored Cloudflare profile in MMKV immediately (typically called
     * right after license redeem or subscription refresh), leaving Amazon/CloudFront untouched.
     */
    fun enrichAllStoredCloudflareProfiles(context: Context): Int {
        val cfCidrs = CdnPingClassifier.getAllCloudflareCidrs(context)
        val frontCidrs = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT)
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }

        var enrichedCount = 0
        for (guid in MmkvManager.decodeAllServerList()) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            OriginDomainStore.rememberIfDomain(guid, profile)
            val origin = OriginDomainStore.getOriginDomain(guid, profile)
            if (!isCloudflareConfig(profile, origin, cfCidrs, frontCidrs)) {
                continue
            }
            val beforeSuites = profile.cipherSuites
            val beforeMask = profile.finalMask
            val beforeFp = profile.fingerPrint
            applyCloudflareHardeningIfEligible(profile, origin, cfCidrs, frontCidrs)
            val changed = beforeSuites != profile.cipherSuites ||
                beforeMask != profile.finalMask ||
                beforeFp != profile.fingerPrint
            val saved = validateAndPersistProfile(guid, profile)
            if (saved != null) {
                if (changed) {
                    LogUtil.i(TAG, "Enriched Cloudflare config $guid (${profile.remarks}) with CipherSuites + TCB finalMask")
                }
                enrichedCount++
            }
        }
        return enrichedCount
    }

    /**
     * Validates finalMask JSON + outbound serialization + ProfileItem round-trip before/after
     * writing into MMKV so broken mutations never reach Xray Core silently.
     */
    fun validateAndPersistProfile(guid: String, profile: ProfileItem): ProfileItem? {
        return try {
            if (!profile.finalMask.isNullOrBlank() && JsonUtil.parseString(profile.finalMask) == null) {
                LogUtil.e(TAG, "Validation failed for $guid (${profile.remarks}): invalid finalMask JSON")
                return null
            }
            val outbound = CoreOutboundBuilder.convert(profile)
            if (outbound == null) {
                LogUtil.e(TAG, "Validation failed for $guid (${profile.remarks}): CoreOutboundBuilder.convert returned null")
                return null
            }
            MmkvManager.encodeServerConfig(guid, profile)
            val reloaded = MmkvManager.decodeServerConfig(guid)
            if (reloaded == null || reloaded.server.isNullOrBlank()) {
                LogUtil.e(TAG, "Validation failed after persistence for $guid (${profile.remarks})")
                return null
            }
            reloaded
        } catch (e: Exception) {
            LogUtil.e(TAG, "Exception enriching/validating config $guid (${profile.remarks}): ${e.message}", e)
            null
        }
    }
}
