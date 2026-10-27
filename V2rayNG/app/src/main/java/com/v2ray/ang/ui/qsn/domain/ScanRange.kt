package com.v2ray.ang.ui.qsn.domain

import kotlin.random.Random

/** One user-managed scan range. */
data class ScanRange(
    val cidr: String,
    val enabled: Boolean = true,
)

enum class RangeError { EMPTY, BAD_FORMAT, BAD_IP, BAD_PREFIX, TOO_LARGE }

sealed interface RangeParseResult {
    data class Valid(val range: ScanRange) : RangeParseResult
    data class Invalid(val reason: RangeError) : RangeParseResult
}

/**
 * CIDR parsing, validation and candidate planning for the IP Ranges screen.
 *
 * Everything here is pure: no Android, no network, no storage. That is what
 * makes the limits testable, and the limits are the point - a typo such as
 * 104.16.0.0/0 must never turn into a multi-million address scan.
 */
object ScanRangeParser {

    /** Smallest prefix we accept: /16 = 65,536 addresses. */
    const val MIN_PREFIX = 16

    /** Hard ceiling on candidates produced by one scan. */
    const val MAX_CANDIDATES = 64

    /** How many addresses are sampled from each range. */
    const val DEFAULT_PER_RANGE = 4

    fun parse(input: String): RangeParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return RangeParseResult.Invalid(RangeError.EMPTY)
        if (trimmed.any { it.isWhitespace() }) return RangeParseResult.Invalid(RangeError.BAD_FORMAT)

        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash != trimmed.lastIndexOf('/')) {
            return RangeParseResult.Invalid(RangeError.BAD_FORMAT)
        }
        val ipPart = trimmed.substring(0, slash)
        val prefixPart = trimmed.substring(slash + 1)
        val address = IpAddress.parse(ipPart)
            ?: return RangeParseResult.Invalid(RangeError.BAD_IP)
        if (prefixPart.isEmpty() || prefixPart.length > 2 || !prefixPart.all { it.isDigit() }) {
            return RangeParseResult.Invalid(RangeError.BAD_PREFIX)
        }
        val prefix = prefixPart.toIntOrNull()
            ?: return RangeParseResult.Invalid(RangeError.BAD_PREFIX)
        if (prefix < 0 || prefix > 32) return RangeParseResult.Invalid(RangeError.BAD_PREFIX)
        if (prefix < MIN_PREFIX) return RangeParseResult.Invalid(RangeError.TOO_LARGE)

        // Normalise to the network address so 104.16.5.9/24 and 104.16.0.0/24
        // are the same range.
        val network = networkAddress(address, prefix)
        return RangeParseResult.Valid(ScanRange(IpAddress.format(network) + "/" + prefix))
    }

    fun hostCount(prefix: Int): Long = 1L shl (32 - prefix)

    fun networkAddress(address: Long, prefix: Int): Long {
        if (prefix <= 0) return 0L
        if (prefix >= 32) return address
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return address and mask
    }

    /** True when [inner] is fully contained in [outer]. */
    fun contains(outer: ScanRange, inner: ScanRange): Boolean {
        val a = split(outer) ?: return false
        val b = split(inner) ?: return false
        if (b.second < a.second) return false
        return networkAddress(b.first, a.second) == a.first
    }

    private fun split(range: ScanRange): Pair<Long, Int>? {
        val slash = range.cidr.indexOf('/')
        if (slash <= 0) return null
        val address = IpAddress.parse(range.cidr.substring(0, slash)) ?: return null
        val prefix = range.cidr.substring(slash + 1).toIntOrNull() ?: return null
        return address to prefix
    }
}

/** Turns enabled ranges into a bounded, de-duplicated candidate list. */
object ScanRangePlanner {

    /**
     * Drops ranges that are fully covered by another enabled range, so two
     * overlapping entries never double the work.
     */
    fun dedupe(ranges: List<ScanRange>): List<ScanRange> {
        val enabled = ranges.filter { it.enabled }
        val kept = mutableListOf<ScanRange>()
        enabled.forEach { candidate ->
            val covered = kept.any { existing ->
                existing.cidr == candidate.cidr || ScanRangeParser.contains(existing, candidate)
            }
            if (!covered) kept.add(candidate)
        }
        return kept
    }

    /**
     * Samples [perRange] addresses from every enabled range.
     *
     * @return at most [maxCandidates] distinct addresses, in a stable order.
     */
    fun plan(
        ranges: List<ScanRange>,
        perRange: Int = ScanRangeParser.DEFAULT_PER_RANGE,
        maxCandidates: Int = ScanRangeParser.MAX_CANDIDATES,
        random: Random = Random.Default,
    ): List<String> {
        if (perRange <= 0 || maxCandidates <= 0) return emptyList()
        val result = LinkedHashSet<String>()
        dedupe(ranges).forEach { range ->
            val slash = range.cidr.indexOf('/')
            if (slash <= 0) return@forEach
            val base = IpAddress.parse(range.cidr.substring(0, slash)) ?: return@forEach
            val prefix = range.cidr.substring(slash + 1).toIntOrNull() ?: return@forEach
            val hosts = ScanRangeParser.hostCount(prefix)
            val usable = if (prefix >= 31) hosts else (hosts - 2L).coerceAtLeast(1L)
            val wanted = minOf(perRange.toLong(), usable).toInt()
            val start = if (prefix >= 31) 0L else 1L
            var guard = 0
            while (result.size < maxCandidates && guard < wanted * 8) {
                guard++
                val offset = start + random.nextLong(usable)
                result.add(IpAddress.format(base + offset))
                if (result.size >= maxCandidates) break
            }
        }
        return result.take(maxCandidates)
    }

    /** Stable short hash of the enabled range set, used in the IP cache key. */
    fun fingerprint(ranges: List<ScanRange>): String {
        val enabled = dedupe(ranges).map { it.cidr }.sorted()
        if (enabled.isEmpty()) return "builtin"
        return IpFingerprint.sha256(enabled.joinToString(",")).substring(0, 12)
    }
}
