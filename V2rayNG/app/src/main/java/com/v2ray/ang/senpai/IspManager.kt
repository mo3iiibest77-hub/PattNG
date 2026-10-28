package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.DiscoverySnapshot
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeOutcome
import com.v2ray.ang.ui.qsn.domain.RangeState
import com.v2ray.ang.util.LogUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything persisted for one ISP's discovery.
 *
 * The old profile held only `goodCidrs` and an index. That was not enough to
 * resume honestly: a failed range and a range that was never reached looked the
 * same, and the index was a position in a list that concurrency had already
 * reordered. [results] now records one final outcome per measured CIDR, and
 * [phase]/[total]/[scanned] describe the run itself.
 *
 * The legacy fields stay because other code and older stored profiles use them.
 * `goodCidrs` is always derived from the Good results when a result list exists,
 * so the two cannot disagree.
 */
data class IspProfile(
    val name: String,
    val goodCidrs: List<String>,
    val manualCidrs: List<String> = emptyList(),
    /** Legacy resume cursor, kept for old readers. */
    val lastScannedIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** One final result per measured CIDR, in measurement order. */
    val results: List<RangeOutcome> = emptyList(),
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    /** Size of the dataset the results belong to. */
    val total: Int = 0,
    /** Number of final results. */
    val scanned: Int = 0,
    val updatedAt: Long = 0L,
    /** The range dataset this ISP's results belong to. */
    val dataset: List<String> = emptyList(),
) {
    val allCidrs: List<String> get() = (manualCidrs + goodCidrs).distinct()

    val goodRanges: List<RangeOutcome>
        get() = results.filter { it.state == RangeState.Good }
            .sortedWith(compareByDescending<RangeOutcome> { it.uploadKBps ?: 0L }.thenBy { it.cidr })

    val failedCount: Int get() = results.count { it.state == RangeState.Failed }
    val errorCount: Int get() = results.count { it.state == RangeState.Error }

    /** True when the last run reached the end of the dataset. */
    val isComplete: Boolean
        get() = phase == DiscoveryPhase.Completed && total > 0 && scanned >= total

    fun toSnapshot(): DiscoverySnapshot = DiscoverySnapshot(
        isp = name,
        phase = phase,
        total = total,
        outcomes = results.associateBy { it.cidr },
        dataset = dataset,
    )

    /**
     * Every range of the dataset with its result, so the screen can show
     * pending ranges too, not only the ones that were already measured.
     */
    fun toStatusRows(): List<RangeOutcome> {
        val byCidr = results.associateBy { it.cidr }
        if (dataset.isEmpty()) {
            if (results.isNotEmpty()) return results.sortedBy { it.cidr }
            // Legacy profile: the old scanner kept only the qualified list.
            return goodCidrs.map { cidr ->
                RangeOutcome(
                    cidr = cidr,
                    state = RangeState.Good,
                    checkedAt = createdAt,
                )
            }
        }
        return dataset.map { cidr ->
            byCidr[cidr] ?: RangeOutcome(cidr = cidr, state = RangeState.Pending)
        }
    }

    fun toQualifiedRanges(): List<QualifiedRange> =
        if (results.isNotEmpty()) {
            goodRanges.map { it.toQualifiedRange() }
        } else {
            // A profile imported from the old SharedPreferences file has only
            // goodCidrs. They were qualified by the old scanner, so they are
            // kept instead of being dropped by the migration.
            goodCidrs.map { cidr ->
                QualifiedRange(
                    cidr = cidr,
                    uploadKBps = 0L,
                    latencyMs = 0L,
                    validatedAt = createdAt,
                )
            }
        }
}

/**
 * Per-ISP discovery storage.
 *
 * Backed by MMKV (the project's only storage engine). Older builds wrote the
 * same JSON to a SharedPreferences file; [loadAll] imports that file once and
 * then never reads it again, so an upgrade keeps every saved range and every
 * manual CIDR.
 *
 * All read-modify-write sequences are serialised: six discovery coroutines can
 * finish at the same time, and an unsynchronised write would drop results.
 */
object IspManager {

    private const val TAG = "IspManager"
    private const val KEY_PROFILES = "qsn_isp_profiles_v2"
    private const val LEGACY_PREF_FILE = "isp_profiles"
    private const val LEGACY_KEY = "profiles"

    /** ISP name used when the user does not name one. */
    const val DEFAULT_ISP = "default"

    private val lock = Any()

    fun saveProfile(ctx: Context, profile: IspProfile) {
        synchronized(lock) {
            val name = normaliseName(profile.name)
            val all = loadAllLocked(ctx).toMutableList()
            all.removeAll { it.name.equals(name, ignoreCase = true) }
            all.add(profile.copy(name = name))
            write(all)
        }
    }

    fun addManualCidr(ctx: Context, ispName: String, cidr: String) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val trimmed = cidr.trim()
            if (trimmed.isEmpty()) return
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx >= 0) {
                val p = all[idx]
                if (!p.manualCidrs.contains(trimmed)) {
                    all[idx] = p.copy(manualCidrs = p.manualCidrs + trimmed)
                }
            } else {
                all.add(IspProfile(name = name, goodCidrs = emptyList(), manualCidrs = listOf(trimmed)))
            }
            write(all)
        }
    }

    fun removeManualCidr(ctx: Context, ispName: String, cidr: String) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx >= 0) {
                all[idx] = all[idx].copy(
                    manualCidrs = all[idx].manualCidrs.filter { it != cidr.trim() },
                )
                write(all)
            }
        }
    }

    /**
     * Writes one final result and keeps the derived lists consistent.
     *
     * Called once per measured range, immediately, so leaving the screen, a
     * crash or a later run that finds nothing cannot lose it.
     */
    fun recordOutcome(ctx: Context, ispName: String, outcome: RangeOutcome) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            val base = if (idx >= 0) all[idx] else IspProfile(name, emptyList())
            val results = base.results.toMutableList()
            val existing = results.indexOfFirst { it.cidr == outcome.cidr }
            if (existing >= 0) results[existing] = outcome else results.add(outcome)
            val good = results.filter { it.state == RangeState.Good }
                .sortedWith(compareByDescending<RangeOutcome> { it.uploadKBps ?: 0L }.thenBy { it.cidr })
                .map { it.cidr }
            val updated = base.copy(
                name = name,
                results = results,
                goodCidrs = good,
                scanned = results.count { it.isTerminal },
                total = maxOf(base.total, results.size),
                updatedAt = System.currentTimeMillis(),
            )
            if (idx >= 0) all[idx] = updated else all.add(updated)
            write(all)
        }
    }

    /** Records the dataset of the current run without touching any result. */
    fun setDataset(ctx: Context, ispName: String, dataset: List<String>) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            val base = if (idx >= 0) all[idx] else IspProfile(name, emptyList())
            val updated = base.copy(
                name = name,
                dataset = dataset.distinct(),
                total = dataset.distinct().size,
                updatedAt = System.currentTimeMillis(),
            )
            if (idx >= 0) all[idx] = updated else all.add(updated)
            write(all)
        }
    }

    fun setPhase(
        ctx: Context,
        ispName: String,
        phase: DiscoveryPhase,
        total: Int,
        scanned: Int,
    ) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            val base = if (idx >= 0) all[idx] else IspProfile(name, emptyList())
            val updated = base.copy(
                name = name,
                phase = phase,
                total = maxOf(total, base.total),
                scanned = scanned.coerceAtLeast(0),
                lastScannedIndex = scanned.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            )
            if (idx >= 0) all[idx] = updated else all.add(updated)
            write(all)
        }
    }

    /**
     * Marks a run whose process died as [DiscoveryPhase.Interrupted].
     *
     * Called once when the UI starts. A live run is never touched: the scanner
     * owns it and publishes its own state.
     */
    fun markInterrupted(ctx: Context, ispName: String) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx < 0) return
            val p = all[idx]
            if (p.phase != DiscoveryPhase.Running) return
            all[idx] = p.copy(phase = DiscoveryPhase.Interrupted, updatedAt = System.currentTimeMillis())
            write(all)
        }
    }

    /** Forgets the results of one ISP and starts a fresh discovery. */
    fun resetDiscovery(ctx: Context, ispName: String) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx < 0) return
            all[idx] = all[idx].copy(
                results = emptyList(),
                goodCidrs = emptyList(),
                phase = DiscoveryPhase.Idle,
                total = all[idx].dataset.size,
                scanned = 0,
                lastScannedIndex = 0,
                updatedAt = System.currentTimeMillis(),
            )
            write(all)
        }
    }

    /**
     * Legacy partial-progress entry point.
     *
     * Kept for the old scanner service and older callers. It only updates the
     * derived list and the cursor; the per-range results are written by
     * [recordOutcome].
     */
    fun savePartialProgress(ctx: Context, ispName: String, foundCidrs: List<String>, lastIndex: Int) {
        synchronized(lock) {
            val name = normaliseName(ispName)
            val all = loadAllLocked(ctx).toMutableList()
            val idx = all.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx >= 0) {
                val p = all[idx]
                all[idx] = p.copy(
                    goodCidrs = (p.goodCidrs + foundCidrs).distinct(),
                    lastScannedIndex = lastIndex,
                    scanned = lastIndex,
                    phase = if (p.phase == DiscoveryPhase.Completed) p.phase else DiscoveryPhase.Paused,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                all.add(
                    IspProfile(
                        name = name,
                        goodCidrs = foundCidrs.distinct(),
                        lastScannedIndex = lastIndex,
                        scanned = lastIndex,
                        phase = DiscoveryPhase.Paused,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            write(all)
        }
    }

    fun loadAll(ctx: Context): List<IspProfile> = synchronized(lock) { loadAllLocked(ctx) }

    fun getProfile(ctx: Context, name: String): IspProfile? {
        val wanted = normaliseName(name)
        return loadAll(ctx).find { it.name.equals(wanted, ignoreCase = true) }
    }

    fun deleteProfile(ctx: Context, name: String) {
        synchronized(lock) {
            val wanted = normaliseName(name)
            val all = loadAllLocked(ctx).filterNot { it.name.equals(wanted, ignoreCase = true) }
            write(all)
        }
    }

    // ---------- Serialisation ----------

    private fun normaliseName(name: String): String =
        name.trim().ifEmpty { DEFAULT_ISP }

    private fun loadAllLocked(ctx: Context): List<IspProfile> {
        val json = readRaw(ctx) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> parseProfile(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            LogUtil.e(TAG, "loadAll failed: " + e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun readRaw(ctx: Context): String? {
        val current = try {
            MmkvManager.decodeSettingsString(KEY_PROFILES)
        } catch (e: Exception) {
            null
        }
        if (!current.isNullOrBlank()) return current

        // One-time migration from the SharedPreferences file the old scanner
        // used. It is copied, not moved: a downgrade still finds its data.
        val legacy = try {
            ctx.getSharedPreferences(LEGACY_PREF_FILE, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY, null)
        } catch (e: Exception) {
            null
        }
        if (!legacy.isNullOrBlank()) {
            try {
                MmkvManager.encodeSettings(KEY_PROFILES, legacy)
            } catch (e: Exception) {
                LogUtil.e(TAG, "legacy import failed: " + e.javaClass.simpleName)
            }
            return legacy
        }
        return null
    }

    private fun write(all: List<IspProfile>) {
        try {
            MmkvManager.encodeSettings(KEY_PROFILES, serialize(all))
        } catch (e: Exception) {
            LogUtil.e(TAG, "write failed: " + e.javaClass.simpleName)
        }
    }

    private fun parseProfile(obj: JSONObject?): IspProfile? {
        if (obj == null) return null
        val name = obj.optString("name", "").trim()
        if (name.isEmpty()) return null
        val results = obj.optJSONArray("results")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> parseOutcome(arr.optJSONObject(i)) }
        }.orEmpty()
        val goodFromResults = results.filter { it.state == RangeState.Good }
            .sortedWith(compareByDescending<RangeOutcome> { it.uploadKBps ?: 0L }.thenBy { it.cidr })
            .map { it.cidr }
        return IspProfile(
            name = name,
            goodCidrs = if (results.isNotEmpty()) {
                goodFromResults
            } else {
                // Legacy profile: only goodCidrs and a cursor existed.
                obj.optJSONArray("goodCidrs")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList()
            },
            manualCidrs = obj.optJSONArray("manualCidrs")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList(),
            lastScannedIndex = obj.optInt("lastScannedIndex", 0),
            createdAt = obj.optLong("createdAt", 0L),
            results = results,
            phase = parsePhase(obj.optString("phase", DiscoveryPhase.Idle.name)),
            total = obj.optInt("total", 0),
            scanned = obj.optInt("scanned", results.count { it.isTerminal }),
            updatedAt = obj.optLong("updatedAt", 0L),
            dataset = obj.optJSONArray("dataset")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList(),
        )
    }

    private fun parseOutcome(obj: JSONObject?): RangeOutcome? {
        if (obj == null) return null
        val cidr = obj.optString("cidr", "").trim()
        if (cidr.isEmpty()) return null
        return RangeOutcome(
            cidr = cidr,
            state = parseState(obj.optString("state", RangeState.Error.name)),
            uploadKBps = obj.optNullableLong("uploadKBps"),
            downloadKBps = obj.optNullableLong("downloadKBps"),
            latencyMs = obj.optNullableLong("latencyMs"),
            attempts = obj.optInt("attempts", 1),
            checkedAt = obj.optLong("checkedAt", 0L),
        )
    }

    private fun parsePhase(raw: String): DiscoveryPhase =
        runCatching { DiscoveryPhase.valueOf(raw) }.getOrDefault(DiscoveryPhase.Idle)

    private fun parseState(raw: String): RangeState =
        runCatching { RangeState.valueOf(raw) }.getOrDefault(RangeState.Error)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun serialize(all: List<IspProfile>): String {
        val arr = JSONArray()
        all.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("goodCidrs", JSONArray(p.goodCidrs))
                    put("manualCidrs", JSONArray(p.manualCidrs))
                    put("lastScannedIndex", p.lastScannedIndex)
                    put("createdAt", p.createdAt)
                    put("phase", p.phase.name)
                    put("total", p.total)
                    put("scanned", p.scanned)
                    put("updatedAt", p.updatedAt)
                    put("dataset", JSONArray(p.dataset))
                    put(
                        "results",
                        JSONArray().apply {
                            p.results.forEach { r ->
                                put(
                                    JSONObject().apply {
                                        put("cidr", r.cidr)
                                        put("state", r.state.name)
                                        r.uploadKBps?.let { put("uploadKBps", it) }
                                        r.downloadKBps?.let { put("downloadKBps", it) }
                                        r.latencyMs?.let { put("latencyMs", it) }
                                        put("attempts", r.attempts)
                                        put("checkedAt", r.checkedAt)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return arr.toString()
    }
}

