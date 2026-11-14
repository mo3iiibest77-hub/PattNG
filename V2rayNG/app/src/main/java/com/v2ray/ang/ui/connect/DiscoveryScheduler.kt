package com.v2ray.ang.ui.connect

import android.content.Context
import com.v2ray.ang.senpai.CloudFrontScanner
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.ui.qsn.data.ProviderRangeStore
import com.v2ray.ang.ui.qsn.domain.EdgeProvider

/**
 * Starts Stage 1 discovery in the background after a connection is up.
 *
 * The scanner owns the run: this object only decides *when* a background pass
 * may start. It deliberately has no job, scope or timeout of its own, because a
 * second owner is exactly what used to let one part of the app cancel a run
 * another part had started.
 *
 * Background probes use the underlying physical network (since CoreVpnService
 * excludes the app package from TUN routing and CloudFront HTTP fetches bind
 * via UnderlyingNetworkProvider).
 *
 * What it deliberately does not do:
 *  - it never writes a profile, an edge or a selection, so it cannot replace the
 *    address the tunnel is using;
 *  - it never starts or restarts the VPN;
 *  - it does nothing while a discovery is already active.
 */
object DiscoveryScheduler {

    /** Range set name used by the ISP-aware discovery path. */
    const val DEFAULT_ISP = IspManager.DEFAULT_ISP

    val isRunning: Boolean
        get() = CloudflareScanner.isDiscoveryActive || CloudFrontScanner.isDiscoveryActive

    /**
     * Starts background ISP discovery for Cloudflare and (when fetched or
     * eligible) Amazon CloudFront, without modifying the active profile or
     * erasing existing seed/manual ranges.
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
        // Only trigger automatic CloudFront range discovery if CloudFront ranges
        // were already synced or are explicitly selected, avoiding unnecessary work
        // for Cloudflare-only deployments.
        val hasCloudFront = ProviderRangeStore.snapshot(EdgeProvider.CLOUDFRONT).fetchedCidrs.isNotEmpty()
        if (hasCloudFront) {
            CloudFrontScanner.discoverRanges(
                context = context,
                guid = guid,
                background = true,
            )
        }
    }

    /**
     * Stops active discovery passes. Only an explicit user stop may call this.
     */
    fun stop() {
        CloudflareScanner.stopDiscovery()
        CloudFrontScanner.stopDiscovery()
    }
}
