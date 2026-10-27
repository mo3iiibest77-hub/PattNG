package com.v2ray.ang.ui.qsn.domain

/**
 * One Cloudflare range that real measurements qualified.
 *
 * The CIDR alone is not proof of quality: a range enters this type only after a
 * candidate sampled from it answered and passed the throughput gate below. The
 * bundled `cf_ranges_v4.txt` lists *candidates*, never qualified ranges.
 */
data class QualifiedRange(
    val cidr: String,
    /** Best upload measured inside the range, in KB/s. 0 means "not measured". */
    val uploadKBps: Long = 0L,
    /** Median latency of the sample that qualified the range, in ms. */
    val latencyMs: Long = 0L,
    /** When the measurement ran, epoch millis. */
    val validatedAt: Long = 0L,
)

/** What a single measured candidate says about the range it was sampled from. */
enum class RangeVerdict {
    /** Answered and carried real upload traffic above the gate. */
    Good,

    /** Answered, but did not reach the throughput gate. */
    NotQualified,

    /** No throughput measurement exists, so the range can be neither accepted nor rejected. */
    NotMeasured,
}

/**
 * The discovery quality rule.
 *
 * This is the rule the historical ISP discovery used
 * (`fix/isp-discovery-quality`, commit `be9f3cef`: a range is good when a
 * sampled IP answers **and** upload is at least 15 KB/s), carried over with the
 * threshold this branch already shipped (20 KB/s). It is deliberately not a
 * latency-only rule: a ping that answers proves reachability, not usable
 * throughput.
 */
object DiscoveryQuality {

    /** Upload a candidate must carry for its range to qualify, in KB/s. */
    const val MIN_UPLOAD_KBPS: Long = 20L

    /** How many qualified ranges a foreground discovery run collects before it pauses. */
    const val TARGET_RANGES: Int = 5

    /**
     * Classifies one measurement.
     *
     * A missing upload measurement is [RangeVerdict.NotMeasured], never a zero:
     * the traffic test may simply not have run, and treating that as failure
     * would throw away a range nobody measured.
     */
    fun verdict(reachable: Boolean, uploadKBps: Long?): RangeVerdict = when {
        !reachable -> RangeVerdict.NotQualified
        uploadKBps == null -> RangeVerdict.NotMeasured
        uploadKBps >= MIN_UPLOAD_KBPS -> RangeVerdict.Good
        else -> RangeVerdict.NotQualified
    }

    fun qualifies(reachable: Boolean, uploadKBps: Long?): Boolean =
        verdict(reachable, uploadKBps) == RangeVerdict.Good
}

/**
 * Collects qualified ranges during one discovery run.
 *
 * It stops accepting once [target] distinct ranges are known, which is what
 * lets a connect attempt move to the candidate scan without waiting for the
 * whole Cloudflare dataset. It is not a cancellation: the caller keeps the
 * accumulator and can hand it to the background run.
 */
class DiscoveryAccumulator(val target: Int = DiscoveryQuality.TARGET_RANGES) {

    private val found = LinkedHashMap<String, QualifiedRange>()

    val qualified: List<QualifiedRange> get() = found.values.toList()

    val size: Int get() = found.size

    val reachedTarget: Boolean get() = found.size >= target

    /** @return true when [range] was new and accepted. */
    fun offer(range: QualifiedRange): Boolean {
        if (reachedTarget) return false
        val cidr = range.cidr.trim()
        if (cidr.isEmpty() || found.containsKey(cidr)) return false
        found[cidr] = range.copy(cidr = cidr)
        return true
    }
}

/**
 * Validation, de-duplication and merging for the persisted range database.
 *
 * Everything here is pure so the storage rules are testable without Android:
 * a corrupt CIDR is dropped, a duplicate is folded into one entry, and the set
 * never grows past [MAX_RANGES].
 */
object RangeSet {

    /** Upper bound on the persisted database, so it cannot grow without limit. */
    const val MAX_RANGES: Int = 64

    /**
     * Normalises [raw] into a valid, distinct, best-first list.
     *
     * A malformed CIDR is dropped instead of being repaired: writing a guess
     * into the database would make later scans probe addresses the user never
     * qualified.
     */
    fun sanitize(raw: List<QualifiedRange>): List<QualifiedRange> {
        val out = LinkedHashMap<String, QualifiedRange>()
        raw.forEach { range ->
            val parsed = ScanRangeParser.parse(range.cidr)
            if (parsed is RangeParseResult.Valid) {
                val cidr = parsed.range.cidr
                val existing = out[cidr]
                if (existing == null || range.uploadKBps > existing.uploadKBps) {
                    out[cidr] = range.copy(cidr = cidr)
                }
            }
        }
        return out.values
            .sortedWith(compareByDescending<QualifiedRange> { it.uploadKBps }.thenBy { it.cidr })
            .take(MAX_RANGES)
    }

    /** Merges newly qualified ranges into the stored set, newest measurement wins. */
    fun merge(
        existing: List<QualifiedRange>,
        found: List<QualifiedRange>,
        limit: Int = MAX_RANGES,
    ): List<QualifiedRange> = sanitize(existing + found).take(limit.coerceAtLeast(0))

    /** The CIDRs of [ranges], in the order [sanitize] produced. */
    fun cidrs(ranges: List<QualifiedRange>): List<String> =
        sanitize(ranges).map { it.cidr }
}

/**
 * Where a discovery run is right now.
 *
 * Paused is not Completed: the foreground run stops as soon as the target
 * number of ranges qualifies, and the rest of the dataset is left for the
 * background pass. Reporting that as "finished" would hide work that is still
 * pending.
 */
enum class DiscoveryPhase {
    /** Nothing has run in this process. */
    Idle,
    /** Measuring ranges right now. */
    Running,
    /** Stopped after reaching the target; more of the dataset is still unmeasured. */
    Paused,
    /** Reached the end of the dataset. */
    Completed,
    /** Cancelled by the user or by a newer operation. */
    Cancelled,
    /** Aborted by an error. */
    Failed,
}

/**
 * Live state of Stage 1, shared by every screen that shows it.
 *
 * It is produced by the scanner itself, so the foreground run and the
 * background pass publish through the same source. A screen that is recreated
 * reads the current value instead of starting another run.
 */
data class DiscoveryStatus(
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    /** True when the run was started by [com.v2ray.ang.ui.connect.DiscoveryScheduler]. */
    val background: Boolean = false,
    /** Ranges measured so far, counting from the resume point. */
    val done: Int = 0,
    /** Total ranges in the bundled dataset. */
    val total: Int = 0,
    /** Distinct ranges qualified during this run. */
    val qualified: Int = 0,
    /** The range most recently measured, for a truthful "where is it now". */
    val lastCidr: String? = null,
) {
    val isActive: Boolean get() = phase == DiscoveryPhase.Running
    val isTerminal: Boolean get() =
        phase == DiscoveryPhase.Completed || phase == DiscoveryPhase.Cancelled ||
            phase == DiscoveryPhase.Failed
}
