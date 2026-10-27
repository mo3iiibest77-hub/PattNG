package com.v2ray.ang.dto

import java.io.Serializable

/**
 * One real traffic measurement of the running core.
 *
 * Produced inside the core service process, which is the only place the native
 * statistics API may be called, and shipped to the UI over the existing
 * broadcast channel. The rates are derived from the byte counters the core
 * reported between two queries, so they are measurements, not estimates.
 *
 * Proxy and direct traffic are kept apart because the notification shows both
 * lines; [upBytesPerSecond] and [downBytesPerSecond] are their sum, which is
 * what "Receive" and "Send" mean for the tunnel.
 *
 * @param available false when the core is not running or has no statistics
 */
data class TrafficSnapshot(
    val upBytesPerSecond: Double = 0.0,
    val downBytesPerSecond: Double = 0.0,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val proxyUpBytesPerSecond: Double = 0.0,
    val proxyDownBytesPerSecond: Double = 0.0,
    val directUpBytesPerSecond: Double = 0.0,
    val directDownBytesPerSecond: Double = 0.0,
    val available: Boolean = false,
) : Serializable
