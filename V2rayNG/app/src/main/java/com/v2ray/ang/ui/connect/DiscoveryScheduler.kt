package com.v2ray.ang.ui.connect

import android.content.Context
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.IspManager

/**
 * Starts Stage 1 discovery in the background after a connection is up.
 *
 * The scanner owns the run: this object only decides *when* a background pass
 * may start. It deliberately has no job, scope or timeout of its own, because a
 * second owner is exactly what used to let one part of the app cancel a run
 * another part had started.
 *
 * What it deliberately does not do:
 *  - it never writes a profile, an edge or a selection, so it cannot replace the
 *    address the tunnel is using;
 *  - it never starts or restarts the VPN;
 *  - it does nothing while a discovery is already active ([CloudflareScanner.isDiscoveryActive]);
 *  - it is not a Service. It lives in the scanner's application-scoped
 *    coroutine, so it survives a screen change but dies with the process; the
 *    persisted phase is then shown as Interrupted and can be resumed.
 */
object DiscoveryScheduler {

    /** Range set name used by the ISP-aware discovery path. */
    const val DEFAULT_ISP = IspManager.DEFAULT_ISP

    val isRunning: Boolean get() = CloudflareScanner.isDiscoveryActive

    /**
     * Starts one background pass for [guid], or does nothing when a run is
     * already active or the ISP is already complete.
     */
    fun start(context: Context, guid: String, ispName: String = DEFAULT_ISP) {
        CloudflareScanner.discoverRanges(
            context = context,
            guid = guid,
            ispName = ispName,
            background = true,
            force = false,
            onFinish = { },
        )
    }

    /**
     * Stops the active run.
     *
     * Only an explicit user stop may call this. The connection flow must not:
     * starting Stage 2 is not a reason to cancel Stage 1.
     */
    fun stop() {
        CloudflareScanner.stopDiscovery()
    }
}

