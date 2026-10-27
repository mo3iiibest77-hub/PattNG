package com.v2ray.ang.ui.qsn.domain

/** The two user-facing connection behaviours, kept apart on purpose. */
enum class ConnectMode {
    /** Connection screen: reuse the saved edge, else scan, else discover. */
    Automatic,

    /** Configurations screen: connect this exact configuration, nothing else. */
    Direct,
}

/** What the automatic path should do before it starts the tunnel. */
sealed interface ConnectDecision {
    /** Connect [guid] as stored: no discovery, no candidate scan, no edge rewrite. */
    data class Direct(val guid: String) : ConnectDecision

    /** The saved edge is still valid, so no network measurement is needed. */
    data object ReuseSavedEdge : ConnectDecision

    /** Stage 2 over the qualified ranges already in the database. */
    data object ScanQualifiedRanges : ConnectDecision

    /** Stage 1 first, then Stage 2 with what it qualified. */
    data object DiscoverThenScan : ConnectDecision
}

/**
 * The connection decision table.
 *
 * Pure and total, so every row of the table is a test rather than a comment in
 * the UI. The inputs are facts the caller reads from storage:
 *
 *  - [savedEdgeUsable]: a selection exists for this configuration fingerprint,
 *    is a real IPv4 literal and is inside its TTL.
 *  - [hasQualifiedRanges]: the persisted range database is not empty.
 *  - [refreshRequested]: the user asked for a rescan, or the ranges were
 *    invalidated, so the saved state must not short-circuit the work.
 *
 * | mode      | saved edge | qualified ranges | refresh | decision            |
 * |-----------|------------|------------------|---------|---------------------|
 * | Direct    | any        | any              | any     | Direct(guid)        |
 * | Automatic | usable     | any              | no      | ReuseSavedEdge      |
 * | Automatic | missing    | yes              | no      | ScanQualifiedRanges |
 * | Automatic | missing    | no               | any     | DiscoverThenScan    |
 * | Automatic | any        | any              | yes     | DiscoverThenScan    |
 */
object ConnectPolicy {

    fun decide(
        mode: ConnectMode,
        guid: String,
        savedEdgeUsable: Boolean,
        hasQualifiedRanges: Boolean,
        refreshRequested: Boolean,
    ): ConnectDecision = when {
        mode == ConnectMode.Direct -> ConnectDecision.Direct(guid)
        refreshRequested -> ConnectDecision.DiscoverThenScan
        savedEdgeUsable -> ConnectDecision.ReuseSavedEdge
        hasQualifiedRanges -> ConnectDecision.ScanQualifiedRanges
        else -> ConnectDecision.DiscoverThenScan
    }

    /** True when [decision] must not run the candidate scanner. */
    fun isDirect(decision: ConnectDecision): Boolean = decision is ConnectDecision.Direct
}
