package com.v2ray.ang.ui.qsn.domain

/** Edge/CDN provider supported by the scanner and range store. */
enum class EdgeProvider(val id: String) {
    CLOUDFLARE("cloudflare"),
    CLOUDFRONT("cloudfront");

    companion object {
        fun fromId(raw: String?): EdgeProvider =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: CLOUDFLARE
    }
}

/** Provenance of a persisted CIDR range inside a provider bucket. */
enum class RangeOrigin(val id: String) {
    SEED("seed"),
    DISCOVERED("discovered"),
    MANUAL("manual"),
    FETCHED("fetched");

    companion object {
        fun fromId(raw: String?): RangeOrigin =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

/** One persisted CIDR entry with provider, provenance, and toggle state. */
data class ManagedRange(
    val cidr: String,
    val provider: EdgeProvider,
    val origin: RangeOrigin,
    val enabled: Boolean = true,
    val uploadKBps: Long = 0L,
    val latencyMs: Long = 0L,
    val validatedAt: Long = 0L,
)

/**
 * Structured range bucket for one [EdgeProvider].
 *
 * Keeps [seedCidrs], [discoveredCidrs], [manualCidrs], and [fetchedCidrs] cleanly
 * separated while exposing a deduplicated, non-truncated [allCidrs] view.
 * Discovery merges only into [discoveredCidrs], never erasing [seedCidrs] or [manualCidrs].
 */
data class ProviderRangeBucket(
    val provider: EdgeProvider,
    val seedCidrs: List<ManagedRange> = emptyList(),
    val discoveredCidrs: List<QualifiedRange> = emptyList(),
    val manualCidrs: List<ManagedRange> = emptyList(),
    val fetchedCidrs: List<ManagedRange> = emptyList(),
    val disabledCidrs: Set<String> = emptySet(),
    val removedSeedCidrs: Set<String> = emptySet(),
) {
    /** Unified, normalized, de-duplicated view across all sources with zero truncation. */
    val allCidrs: List<ManagedRange>
        get() {
            val map = LinkedHashMap<String, ManagedRange>()
            seedCidrs.forEach { item ->
                val norm = normalizeCidrOrNull(item.cidr) ?: return@forEach
                if (norm in removedSeedCidrs) return@forEach
                map[norm] = item.copy(
                    cidr = norm,
                    provider = provider,
                    origin = RangeOrigin.SEED,
                    enabled = item.enabled && norm !in disabledCidrs,
                )
            }
            discoveredCidrs.forEach { q ->
                val norm = normalizeCidrOrNull(q.cidr) ?: return@forEach
                val prev = map[norm]
                map[norm] = ManagedRange(
                    cidr = norm,
                    provider = provider,
                    origin = prev?.origin ?: RangeOrigin.DISCOVERED,
                    enabled = (prev?.enabled ?: true) && norm !in disabledCidrs,
                    uploadKBps = maxOf(prev?.uploadKBps ?: 0L, q.uploadKBps),
                    latencyMs = if (q.latencyMs > 0L) q.latencyMs else (prev?.latencyMs ?: 0L),
                    validatedAt = maxOf(prev?.validatedAt ?: 0L, q.validatedAt),
                )
            }
            manualCidrs.forEach { item ->
                val norm = normalizeCidrOrNull(item.cidr) ?: return@forEach
                val prev = map[norm]
                map[norm] = item.copy(
                    cidr = norm,
                    provider = provider,
                    origin = prev?.origin ?: RangeOrigin.MANUAL,
                    enabled = item.enabled && norm !in disabledCidrs,
                    uploadKBps = maxOf(prev?.uploadKBps ?: 0L, item.uploadKBps),
                    latencyMs = if (item.latencyMs > 0L) item.latencyMs else (prev?.latencyMs ?: 0L),
                    validatedAt = maxOf(prev?.validatedAt ?: 0L, item.validatedAt),
                )
            }
            fetchedCidrs.forEach { item ->
                val norm = normalizeCidrOrNull(item.cidr) ?: return@forEach
                val prev = map[norm]
                if (prev == null) {
                    map[norm] = item.copy(
                        cidr = norm,
                        provider = provider,
                        origin = RangeOrigin.FETCHED,
                        enabled = item.enabled && norm !in disabledCidrs,
                    )
                }
            }
            return map.values.toList()
        }

    /** Enabled CIDRs ordered best-first for initial and auto-optimization scans. */
    val enabledScanCidrs: List<String>
        get() {
            val all = allCidrs.filter { it.enabled }
            // Prefer validated/discovered ranges first (strongest upload first),
            // then seeds/manual/fetched so first-run connections scan immediately.
            val discoveredFirst = all.filter { it.uploadKBps > 0L || it.origin == RangeOrigin.DISCOVERED }
                .sortedByDescending { it.uploadKBps }
                .map { it.cidr }
            val rest = all.filterNot { it.uploadKBps > 0L || it.origin == RangeOrigin.DISCOVERED }
                .map { it.cidr }
            return (discoveredFirst + rest).distinct()
        }

    /** Enabled discovered + seed + manual CIDRs formatted as [ScanRange]s. */
    fun toScanRanges(): List<ScanRange> =
        allCidrs.map { ScanRange(cidr = it.cidr, enabled = it.enabled) }

    /** Searchable filter by CIDR substring or origin name. */
    fun search(query: String, originFilter: RangeOrigin? = null): List<ManagedRange> {
        val needle = query.trim().lowercase()
        return allCidrs.filter { entry ->
            (originFilter == null || entry.origin == originFilter) &&
                (needle.isEmpty() ||
                    entry.cidr.lowercase().contains(needle) ||
                    entry.origin.id.contains(needle))
        }
    }

    /** Non-destructively merges newly discovered [found] into [discoveredCidrs]. */
    fun mergeDiscovered(found: List<QualifiedRange>): ProviderRangeBucket {
        if (found.isEmpty()) return this
        val existing = LinkedHashMap<String, QualifiedRange>()
        discoveredCidrs.forEach { item ->
            val norm = normalizeCidrOrNull(item.cidr) ?: return@forEach
            existing[norm] = item.copy(cidr = norm)
        }
        found.forEach { item ->
            val norm = normalizeCidrOrNull(item.cidr) ?: return@forEach
            val prev = existing[norm]
            existing[norm] = if (prev == null || item.uploadKBps >= prev.uploadKBps) {
                item.copy(cidr = norm)
            } else {
                prev
            }
        }
        return copy(
            discoveredCidrs = existing.values
                .sortedWith(compareByDescending<QualifiedRange> { it.uploadKBps }.thenBy { it.cidr }),
        )
    }

    companion object {
        /** Normalizes any valid IPv4 CIDR (/0../32) to network/prefix notation, or null if invalid. */
        fun normalizeCidrOrNull(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash != trimmed.lastIndexOf('/')) return null
            val ip = IpAddress.parse(trimmed.substring(0, slash)) ?: return null
            val prefixStr = trimmed.substring(slash + 1)
            if (prefixStr.isEmpty() || prefixStr.length > 2 || !prefixStr.all { it.isDigit() }) return null
            val prefix = prefixStr.toIntOrNull() ?: return null
            if (prefix < 0 || prefix > 32) return null
            val net = ScanRangeParser.networkAddress(ip, prefix)
            return IpAddress.format(net) + "/" + prefix
        }
    }
}
