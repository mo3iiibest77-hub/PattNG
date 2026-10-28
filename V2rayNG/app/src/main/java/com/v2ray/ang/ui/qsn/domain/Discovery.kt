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
 * This is the rule the historical ISP discovery used: a range is good when a
 * sampled IP answers **and** carries real upload traffic above the gate. The
 * last pre-redesign implementation on `master` (`e9ca5d26`, built on
 * `be9f3cef`) used 20 KB/s, two sampled addresses per range and six concurrent
 * ranges; those are the numbers kept here. It is deliberately not a latency-only
 * rule: a ping that answers proves reachability, not usable throughput.
 */
object DiscoveryQuality {

    /** Upload a candidate must carry for its range to qualify, in KB/s. */
    const val MIN_UPLOAD_KBPS: Long = 20L

    /** Addresses sampled from one range during discovery (historical value). */
    const val IPS_PER_RANGE: Int = 2

    /** Ranges measured at the same time during discovery (historical value). */
    const val CONCURRENCY: Int = 6

    /**
     * Attempts for a range whose probe failed with an *uncertain* result
     * (native/config error, or an answer without a throughput measurement).
     *
     * A range that answered and was measured is final after one attempt: a slow
     * answer is a real verdict, not a transient error. This retry policy is new
     * (the historical code had none) and exists because the requirement asks for
     * temporary errors to be retried without stopping the whole run.
     */
    const val MAX_ATTEMPTS: Int = 2

    /** Delay before retrying an uncertain range. */
    const val RETRY_DELAY_MS: Long = 250L

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
 * It never stops accepting: discovery must reach the end of the dataset, so the
 * only limit is the dataset itself. Kept as a small pure helper because the
 * range list is also assembled in tests and in the background pass.
 */
class DiscoveryAccumulator {

    private val found = LinkedHashMap<String, QualifiedRange>()

    val qualified: List<QualifiedRange> get() = found.values.toList()

    val size: Int get() = found.size

    /** @return true when [range] was new and accepted. */
    fun offer(range: QualifiedRange): Boolean {
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

    /**
     * Upper bound on the persisted database, so a corrupt file cannot grow
     * without limit.
     *
     * It is deliberately far above the bundled dataset (628 CIDRs at the last
     * audit). The old value of 64 silently truncated discovery results; a limit
     * that the real dataset can reach is a hidden cap, not a safety net.
     */
    const val MAX_RANGES: Int = 1024

    /**
     * Normalises [raw] into a valid, distinct, best-first list.
     *
     * A malformed CIDR is dropped instead of repaired, because a wrong range
     * would make the scanner measure addresses outside Cloudflare.
     */
    fun sanitize(raw: List<QualifiedRange>): List<QualifiedRange> {
        val out = LinkedHashMap<String, QualifiedRange>()
        raw.forEach { range ->
            val parsed = ScanRangeParser.parse(range.cidr)
            if (parsed !is RangeParseResult.Valid) return@forEach
            val cidr = parsed.range.cidr
            val existing = out[cidr]
            if (existing == null || range.uploadKBps > existing.uploadKBps) {
                out[cidr] = range.copy(cidr = cidr)
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
 * [Paused] is a user stop and [Interrupted] is a run whose process died; both
 * keep every completed range and are resumable. Neither is [Completed]: the
 * rest of the dataset is still unmeasured, and reporting it as finished would
 * hide pending work.
 */
enum class DiscoveryPhase {
    /** Nothing has run yet for this ISP. */
    Idle,
    /** Measuring ranges right now. */
    Running,
    /** Stopped by the user; every completed result is kept. */
    Paused,
    /** Reached the end of the dataset. */
    Completed,
    /** Cancelled by a newer operation (legacy state, still readable). */
    Cancelled,
    /** The process died while the run was active. Resumable. */
    Interrupted,
    /** Aborted by an unrecoverable error. Completed results are kept. */
    Failed,
}

/**
 * Live state of Stage 1, shared by every screen that shows it.
 *
 * It is produced by the scanner itself, so the foreground run and the background
 * pass publish through the same source. A screen that is recreated reads the
 * current value instead of starting another run.
 */
data class DiscoveryStatus(
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    /** True when the run was started by [com.v2ray.ang.ui.connect.DiscoveryScheduler]. */
    val background: Boolean = false,
    /** ISP this run belongs to. Empty when nothing has run. */
    val ispName: String = "",
    /** Ranges measured so far, including results restored from storage. */
    val done: Int = 0,
    /** Total ranges in the bundled dataset. */
    val total: Int = 0,
    /** Ranges that qualified, restored results included. */
    val qualified: Int = 0,
    /** Ranges that were measured and did not qualify. */
    val failed: Int = 0,
    /** Ranges whose measurement is uncertain and can be retried. */
    val errors: Int = 0,
    /** The range most recently measured, for a truthful "where is it now". */
    val lastCidr: String? = null,
    /** Every range of the run, so the screen can show one row per range. */
    val rows: List<RangeOutcome> = emptyList(),
) {
    val isActive: Boolean get() = phase == DiscoveryPhase.Running
    val isTerminal: Boolean get() =
        phase == DiscoveryPhase.Completed || phase == DiscoveryPhase.Cancelled ||
            phase == DiscoveryPhase.Failed
    /** True when the run can be continued without losing a result. */
    val isResumable: Boolean get() =
        phase == DiscoveryPhase.Paused || phase == DiscoveryPhase.Interrupted ||
            phase == DiscoveryPhase.Failed

    /** Progress in whole percent, 0 when the dataset is unknown. */
    val percent: Int
        get() = if (total <= 0) 0 else (done.toLong() * 100L / total.toLong()).toInt().coerceIn(0, 100)
}

