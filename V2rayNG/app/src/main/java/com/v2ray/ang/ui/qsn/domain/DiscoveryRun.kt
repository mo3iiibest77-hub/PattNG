package com.v2ray.ang.ui.qsn.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * State of one range inside one ISP discovery run.
 *
 * [Pending] and [Running] are transient: only [Good], [Failed] and [Error] are
 * written to storage, because a range that was being measured when the process
 * died must be measured again on resume.
 */
enum class RangeState { Pending, Running, Good, Failed, Error }

/** One persisted discovery result for one CIDR. */
data class RangeOutcome(
    val cidr: String,
    val state: RangeState,
    /** Best upload measured inside the range, KB/s. Null means "not measured". */
    val uploadKBps: Long? = null,
    /** Best download measured inside the range, KB/s. Null means "not measured". */
    val downloadKBps: Long? = null,
    /** Median latency of the sample that decided the range, ms. */
    val latencyMs: Long? = null,
    /** Probe attempts this result took, including retries. */
    val attempts: Int = 1,
    /** When the measurement finished, epoch millis. */
    val checkedAt: Long = 0L,
) {
    /** True when this result is final and must not be measured again on resume. */
    val isTerminal: Boolean get() = state == RangeState.Good || state == RangeState.Failed

    /** True when a later run may try this range again. */
    val isRetryable: Boolean get() = state == RangeState.Error

    /** The qualified-range shape used by the candidate database. */
    fun toQualifiedRange(): QualifiedRange = QualifiedRange(
        cidr = cidr,
        uploadKBps = uploadKBps ?: 0L,
        latencyMs = latencyMs ?: 0L,
        validatedAt = checkedAt,
    )
}

/** What one probe of one range measured. */
data class RangeMeasurement(
    /** True when at least one sampled address answered through the real config. */
    val reachable: Boolean,
    /** Best upload of the sample, KB/s; null when no traffic measurement exists. */
    val uploadKBps: Long? = null,
    /** Best download of the sample, KB/s; null when no traffic measurement exists. */
    val downloadKBps: Long? = null,
    /** Best median latency of the sample, ms; null when nothing answered. */
    val latencyMs: Long? = null,
    /**
     * True when the probe itself failed (native/config error), rather than the
     * address simply not answering. Only this kind of result is retried.
     */
    val error: Boolean = false,
)

/** Everything the engine knows about one ISP before and during a run. */
data class DiscoverySnapshot(
    val isp: String,
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    val total: Int = 0,
    val outcomes: Map<String, RangeOutcome> = emptyMap(),
    /** The dataset this run belongs to, so pending ranges can be shown too. */
    val dataset: List<String> = emptyList(),
) {
    val done: Int get() = outcomes.values.count { it.isTerminal }
    val good: Int get() = outcomes.values.count { it.state == RangeState.Good }
    val failed: Int get() = outcomes.values.count { it.state == RangeState.Failed }
    val errors: Int get() = outcomes.values.count { it.state == RangeState.Error }
}

/** Storage the engine writes through, so the engine stays testable without Android. */
interface DiscoveryStore {
    fun snapshot(isp: String): DiscoverySnapshot
    fun record(isp: String, outcome: RangeOutcome)
    fun setPhase(isp: String, phase: DiscoveryPhase, total: Int, scanned: Int)
}

/** Result of one engine run. */
data class DiscoveryRunSummary(
    val isp: String,
    val scanned: Int,
    val total: Int,
    val good: Int,
    val failed: Int,
    val errors: Int,
    /** True when every range of the dataset has a final result. */
    val completed: Boolean,
    /** Ranges still without a final result, in dataset order. */
    val pending: List<String> = emptyList(),
)

/**
 * Which ranges a run still has to measure.
 *
 * Only [RangeState.Good] and [RangeState.Failed] are final. A range that was
 * pending, running when the process died, or uncertain (Error) is measured
 * again; a completed result is never thrown away and never re-probed by a plain
 * resume.
 */
object DiscoveryPlan {

    fun pending(dataset: List<String>, snapshot: DiscoverySnapshot): List<String> {
        val seen = LinkedHashSet<String>()
        return dataset.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it) }
            .filter { cidr ->
                val outcome = snapshot.outcomes[cidr]
                outcome == null || !outcome.isTerminal
            }
            .toList()
    }
}

/** Turns one measurement into the persisted verdict. Pure, so it is unit-tested. */
object DiscoveryVerdict {

    fun of(cidr: String, measurement: RangeMeasurement, attempts: Int, now: Long): RangeOutcome {
        val state = when {
            measurement.error -> RangeState.Error
            !measurement.reachable -> RangeState.Failed
            // Answered but no throughput measurement: not good enough to keep,
            // and not a proven failure either.
            measurement.uploadKBps == null -> RangeState.Error
            measurement.uploadKBps >= DiscoveryQuality.MIN_UPLOAD_KBPS -> RangeState.Good
            else -> RangeState.Failed
        }
        return RangeOutcome(
            cidr = cidr.trim(),
            state = state,
            uploadKBps = measurement.uploadKBps,
            downloadKBps = measurement.downloadKBps,
            latencyMs = measurement.latencyMs,
            attempts = attempts.coerceAtLeast(1),
            checkedAt = now,
        )
    }
}

/**
 * Runs a discovery pass over every range that has no final result.
 *
 * The engine has no Android dependency: the probe and the store are injected, so
 * the exact behaviour the requirement asks for (measure every range, keep going
 * after a failure, persist each result as it arrives, resume without redoing
 * completed ranges) is testable on the JVM.
 *
 * It never stops early. A range that fails is recorded and the run continues;
 * only an uncertain result is retried, at most [maxAttempts] times. Cancellation
 * is propagated and every result already recorded stays in the store.
 */
class DiscoveryEngine(
    private val store: DiscoveryStore,
    private val probe: suspend (String) -> RangeMeasurement,
    private val concurrency: Int = DiscoveryQuality.CONCURRENCY,
    private val maxAttempts: Int = DiscoveryQuality.MAX_ATTEMPTS,
    private val retryDelayMs: Long = DiscoveryQuality.RETRY_DELAY_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun run(
        isp: String,
        dataset: List<String>,
        onStart: (String) -> Unit = {},
        onOutcome: (RangeOutcome) -> Unit = {},
    ): DiscoveryRunSummary = coroutineScope {
        val before = store.snapshot(isp)
        val pending = DiscoveryPlan.pending(dataset, before)
        if (pending.isEmpty()) {
            store.setPhase(isp, DiscoveryPhase.Completed, dataset.size, before.done)
            return@coroutineScope summary(isp, dataset, store.snapshot(isp))
        }

        store.setPhase(isp, DiscoveryPhase.Running, dataset.size, before.done)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val jobs = pending.map { cidr ->
            launch {
                semaphore.withPermit {
                    onStart(cidr)
                    val outcome = measure(cidr)
                    store.record(isp, outcome)
                    onOutcome(outcome)
                }
            }
        }
        jobs.joinAll()

        val after = store.snapshot(isp)
        val stillPending = DiscoveryPlan.pending(dataset, after)
        store.setPhase(
            isp = isp,
            phase = if (stillPending.isEmpty()) DiscoveryPhase.Completed else DiscoveryPhase.Paused,
            total = dataset.size,
            scanned = after.done,
        )
        summary(isp, dataset, store.snapshot(isp))
    }

    private suspend fun measure(cidr: String): RangeOutcome {
        var attempts = 0
        while (true) {
            attempts++
            val measurement = try {
                probe(cidr)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A native or config failure is uncertain, not a dead range.
                RangeMeasurement(reachable = false, error = true)
            }
            val outcome = DiscoveryVerdict.of(cidr, measurement, attempts, now())
            if (!outcome.isRetryable || attempts >= maxAttempts.coerceAtLeast(1)) {
                return outcome
            }
            if (retryDelayMs > 0L) delay(retryDelayMs)
        }
    }

    private fun summary(
        isp: String,
        dataset: List<String>,
        snapshot: DiscoverySnapshot,
    ): DiscoveryRunSummary {
        val pending = DiscoveryPlan.pending(dataset, snapshot)
        return DiscoveryRunSummary(
            isp = isp,
            scanned = snapshot.done,
            total = dataset.size,
            good = snapshot.good,
            failed = snapshot.failed,
            errors = snapshot.errors,
            completed = pending.isEmpty(),
            pending = pending,
        )
    }
}

