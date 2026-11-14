package com.v2ray.ang.senpai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.handler.MmkvManager

/**
 * Simple key-value storage abstraction so [ProviderRangeRepository] is fully unit-testable on JVM.
 */
interface RangeKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

internal object MmkvRangeKeyValueStore : RangeKeyValueStore {
    override fun getString(key: String): String? = MmkvManager.decodeSettingsString(key)
    override fun putString(key: String, value: String) {
        MmkvManager.encodeSettings(key, value)
    }
}

internal data class PersistedProviderRanges(
    @SerializedName("seeds") val seeds: List<String> = emptyList(),
    @SerializedName("fetched") val fetched: List<String> = emptyList(),
    @SerializedName("discovered") val discovered: List<String> = emptyList(),
    @SerializedName("manual") val manual: List<String> = emptyList(),
    @SerializedName("disabled") val disabled: List<String> = emptyList(),
    @SerializedName("last_synced_at") val lastSyncedAt: Long = 0L,
)

/**
 * Separate persistent range repository for [CdnType.CLOUDFLARE] and [CdnType.CLOUDFRONT].
 *
 * Guarantees:
 * - Cloudflare Seed CIDRs are initialized with the exact 46 [CloudflareSeedRanges.SEED_CIDRS] and
 *   are never overwritten by Discovery runs.
 * - CloudFront official CIDRs are loaded from `assets/cloudfront_ranges_v4.txt` and refreshed
 *   from AWS `ip-ranges.json`.
 * - Every list displays in full with zero truncation limit.
 */
class ProviderRangeRepository(
    private val store: RangeKeyValueStore = MmkvRangeKeyValueStore,
    private val defaultCloudFrontCidrsProvider: () -> List<String> = { emptyList() },
) {
    private val gson = Gson()
    private val lock = Any()

    fun getEntries(provider: CdnType): List<ProviderRangeEntry> = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return emptyList()
        val data = ensureInitializedLocked(provider)
        val disabledSet = data.disabled.toSet()
        val seen = linkedSetOf<String>()
        val result = mutableListOf<ProviderRangeEntry>()

        fun append(list: List<String>, origin: RangeOrigin) {
            for (raw in list) {
                val cidr = CloudflareSeedRanges.normalizeIpv4Cidr(raw) ?: continue
                if (seen.add(cidr)) {
                    result.add(
                        ProviderRangeEntry(
                            cidr = cidr,
                            provider = provider,
                            origin = origin,
                            enabled = cidr !in disabledSet
                        )
                    )
                }
            }
        }

        // Priority order: discovered ISP ranges -> manual ranges -> seed ranges -> fetched AWS ranges
        append(data.discovered, RangeOrigin.DISCOVERED)
        append(data.manual, RangeOrigin.MANUAL)
        append(data.seeds, RangeOrigin.SEED)
        append(data.fetched, RangeOrigin.FETCHED)
        return result
    }

    /**
     * Returns enabled CIDRs ordered cleanly for regular IP scanning.
     *
     * For [CdnType.CLOUDFLARE], always includes enabled Seeds + Discovered + Manual.
     * For [CdnType.CLOUDFRONT], prefers Discovered + Manual first if present, falling back to enabled Fetched CIDRs.
     */
    fun getScanPoolCidrs(provider: CdnType): List<String> = synchronized(lock) {
        val entries = getEntries(provider).filter { it.enabled }
        if (provider == CdnType.CLOUDFRONT) {
            val priority = entries.filter {
                it.origin == RangeOrigin.DISCOVERED || it.origin == RangeOrigin.MANUAL
            }.map { it.cidr }
            val fetched = entries.filter { it.origin == RangeOrigin.FETCHED }.map { it.cidr }
            return (priority + fetched).distinct()
        }
        return entries.map { it.cidr }.distinct()
    }

    /**
     * Returns all candidate CIDRs used when running full Discovery for [provider].
     */
    fun getDiscoveryBaseCidrs(provider: CdnType): List<String> = synchronized(lock) {
        val data = ensureInitializedLocked(provider)
        return when (provider) {
            CdnType.CLOUDFRONT -> (data.fetched + data.manual + data.seeds).distinct()
            CdnType.CLOUDFLARE -> (data.seeds + data.manual).distinct()
            CdnType.UNKNOWN -> emptyList()
        }
    }

    /**
     * Appends newly discovered working CIDRs into [provider] without removing seeds, fetched, or manual ranges.
     */
    fun appendDiscoveredCidrs(provider: CdnType, newCidrs: List<String>): List<String> = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return emptyList()
        val data = ensureInitializedLocked(provider)
        val normalized = newCidrs.mapNotNull { CloudflareSeedRanges.normalizeIpv4Cidr(it) }
        val merged = (data.discovered + normalized).distinct()
        saveLocked(provider, data.copy(discovered = merged))
        return merged
    }

    /**
     * Replaces/updates official fetched AWS CIDRs for [CdnType.CLOUDFRONT] without losing discovered or manual ranges.
     */
    fun updateFetchedCidrs(provider: CdnType, fetchedCidrs: List<String>, syncedAt: Long = System.currentTimeMillis()): List<String> =
        synchronized(lock) {
            if (provider == CdnType.UNKNOWN) return emptyList()
            val data = ensureInitializedLocked(provider)
            val normalized = fetchedCidrs.mapNotNull { CloudflareSeedRanges.normalizeIpv4Cidr(it) }.distinct()
            saveLocked(provider, data.copy(fetched = normalized, lastSyncedAt = syncedAt))
            return normalized
        }

    fun addManualCidr(provider: CdnType, rawCidr: String): Boolean = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return false
        val cidr = CloudflareSeedRanges.normalizeIpv4Cidr(rawCidr) ?: return false
        val data = ensureInitializedLocked(provider)
        val mergedManual = (listOf(cidr) + data.manual).distinct()
        val disabled = data.disabled.filterNot { it == cidr }
        saveLocked(provider, data.copy(manual = mergedManual, disabled = disabled))
        return true
    }

    fun removeCidr(provider: CdnType, rawCidr: String): Unit = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return
        val cidr = CloudflareSeedRanges.normalizeIpv4Cidr(rawCidr) ?: return
        val data = ensureInitializedLocked(provider)
        saveLocked(
            provider,
            data.copy(
                seeds = data.seeds.filterNot { it == cidr },
                fetched = data.fetched.filterNot { it == cidr },
                discovered = data.discovered.filterNot { it == cidr },
                manual = data.manual.filterNot { it == cidr },
                disabled = data.disabled.filterNot { it == cidr },
            )
        )
    }

    fun setCidrEnabled(provider: CdnType, rawCidr: String, enabled: Boolean): Unit = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return
        val cidr = CloudflareSeedRanges.normalizeIpv4Cidr(rawCidr) ?: return
        val data = ensureInitializedLocked(provider)
        val nextDisabled = if (enabled) {
            data.disabled.filterNot { it == cidr }
        } else {
            (data.disabled + cidr).distinct()
        }
        saveLocked(provider, data.copy(disabled = nextDisabled))
    }

    fun clearDiscovered(provider: CdnType): Unit = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return
        val data = ensureInitializedLocked(provider)
        saveLocked(provider, data.copy(discovered = emptyList()))
    }

    fun resetSeeds(provider: CdnType): Unit = synchronized(lock) {
        if (provider != CdnType.CLOUDFLARE) return
        val data = ensureInitializedLocked(provider)
        val seedSet = CloudflareSeedRanges.SEED_CIDRS.toSet()
        saveLocked(
            provider,
            data.copy(
                seeds = CloudflareSeedRanges.SEED_CIDRS,
                disabled = data.disabled.filterNot { it in seedSet }
            )
        )
    }

    fun getLastSyncedAt(provider: CdnType): Long = synchronized(lock) {
        if (provider == CdnType.UNKNOWN) return 0L
        return ensureInitializedLocked(provider).lastSyncedAt
    }

    private fun ensureInitializedLocked(provider: CdnType): PersistedProviderRanges {
        val key = storageKey(provider)
        val raw = store.getString(key)
        val decoded = raw?.let {
            runCatching { gson.fromJson(it, PersistedProviderRanges::class.java) }.getOrNull()
        }
        if (decoded != null) {
            // Guarantee Cloudflare seeds exist on first launch / migration without removing anything else
            if (provider == CdnType.CLOUDFLARE && decoded.seeds.isEmpty() && raw.contains("\"seeds\":[]")) {
                val seeded = decoded.copy(seeds = CloudflareSeedRanges.SEED_CIDRS)
                saveLocked(provider, seeded)
                return seeded
            }
            if (provider == CdnType.CLOUDFRONT && decoded.fetched.isEmpty()) {
                val defaults = defaultCloudFrontCidrsProvider()
                if (defaults.isNotEmpty()) {
                    val populated = decoded.copy(fetched = defaults)
                    saveLocked(provider, populated)
                    return populated
                }
            }
            return decoded
        }

        val initial = when (provider) {
            CdnType.CLOUDFLARE -> PersistedProviderRanges(
                seeds = CloudflareSeedRanges.SEED_CIDRS
            )
            CdnType.CLOUDFRONT -> PersistedProviderRanges(
                fetched = defaultCloudFrontCidrsProvider()
            )
            CdnType.UNKNOWN -> PersistedProviderRanges()
        }
        saveLocked(provider, initial)
        return initial
    }

    private fun saveLocked(provider: CdnType, state: PersistedProviderRanges) {
        store.putString(storageKey(provider), gson.toJson(state))
    }

    private fun storageKey(provider: CdnType): String = when (provider) {
        CdnType.CLOUDFLARE -> "qsn_provider_ranges_cf_v1"
        CdnType.CLOUDFRONT -> "qsn_provider_ranges_cfront_v1"
        CdnType.UNKNOWN -> "qsn_provider_ranges_unknown"
    }
}

object ProviderRangeStore {

    private var defaultAssetContext: Context? = null

    private val repo by lazy {
        ProviderRangeRepository(
            store = MmkvRangeKeyValueStore,
            defaultCloudFrontCidrsProvider = { loadBundledCloudFrontCidrs(defaultAssetContext) }
        )
    }

    fun init(context: Context) {
        defaultAssetContext = context.applicationContext
    }

    fun getEntries(context: Context, provider: CdnType): List<ProviderRangeEntry> {
        init(context)
        return repo.getEntries(provider)
    }

    fun getScanPoolCidrs(context: Context, provider: CdnType): List<String> {
        init(context)
        return repo.getScanPoolCidrs(provider)
    }

    fun appendDiscoveredCidrs(context: Context, provider: CdnType, newCidrs: List<String>): List<String> {
        init(context)
        return repo.appendDiscoveredCidrs(provider, newCidrs)
    }

    fun updateFetchedCidrs(context: Context, provider: CdnType, fetchedCidrs: List<String>): List<String> {
        init(context)
        return repo.updateFetchedCidrs(provider, fetchedCidrs)
    }

    fun addManualCidr(context: Context, provider: CdnType, rawCidr: String): Boolean {
        init(context)
        return repo.addManualCidr(provider, rawCidr)
    }

    fun removeCidr(context: Context, provider: CdnType, rawCidr: String) {
        init(context)
        repo.removeCidr(provider, rawCidr)
    }

    fun setCidrEnabled(context: Context, provider: CdnType, rawCidr: String, enabled: Boolean) {
        init(context)
        repo.setCidrEnabled(provider, rawCidr, enabled)
    }

    fun clearDiscovered(context: Context, provider: CdnType) {
        init(context)
        repo.clearDiscovered(provider)
    }

    fun resetCloudflareSeeds(context: Context) {
        init(context)
        repo.resetSeeds(CdnType.CLOUDFLARE)
    }

    fun getLastSyncedAt(context: Context, provider: CdnType): Long {
        init(context)
        return repo.getLastSyncedAt(provider)
    }

    fun loadBundledCloudFrontCidrs(context: Context?): List<String> {
        val ctx = context ?: return emptyList()
        return runCatching {
            ctx.assets.open("cloudfront_ranges_v4.txt")
                .bufferedReader()
                .use { CloudFrontIpRangesParser.parseCidrLines(it.readText()) }
        }.getOrDefault(emptyList())
    }

    /**
     * Downloads latest AWS CloudFront IPv4 prefixes and saves them without touching discovered or manual ranges.
     */
    fun syncCloudFrontFromAws(context: Context): Result<List<String>> {
        init(context)
        val client = PhysicalNetworkGuard.createPhysicalOkHttpClient(
            context,
            connectTimeoutSec = 6L,
            readTimeoutSec = 8L,
            requirePhysical = true,
        ) ?: return Result.failure(IllegalStateException("No physical non-VPN internet available"))
        return CloudFrontIpRangesParser.fetchLatestCloudFrontCidrs(client = client).map { cidrs ->
            repo.updateFetchedCidrs(CdnType.CLOUDFRONT, cidrs)
            cidrs
        }
    }
}
