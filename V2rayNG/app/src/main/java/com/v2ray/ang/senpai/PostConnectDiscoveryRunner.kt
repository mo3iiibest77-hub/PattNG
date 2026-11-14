package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class PostConnectDiscoveryState(
    val running: Boolean = false,
    val activeProvider: CdnType? = null,
    val done: Int = 0,
    val total: Int = 0,
    val currentCidr: String = "",
    val cloudFrontFound: Int = 0,
    val cloudflareFound: Int = 0,
    val completedOnce: Boolean = false,
)

/**
 * Executes automatic ISP Discovery over the physical uplink (`com.quietstorm.ng` bypasses the
 * VPN tunnel in [com.v2ray.ang.service.CoreVpnService]) right after the first successful VPN
 * connection:
 * 1) CloudFront Discovery
 * 2) Cloudflare Discovery (preserving all 46 seed CIDRs intact).
 */
object PostConnectDiscoveryRunner {

    private const val TAG = "PostConnectDiscovery"
    private const val PREF_FIRST_CONNECT_DISCOVERY_DONE = "qsn_first_connect_discovery_done"
    private const val PREF_AUTO_POST_CONNECT_DISCOVERY = "qsn_auto_post_connect_discovery"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(PostConnectDiscoveryState())
    val state: StateFlow<PostConnectDiscoveryState> = _state.asStateFlow()

    fun isAutoDiscoveryEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(PREF_AUTO_POST_CONNECT_DISCOVERY, true)

    fun setAutoDiscoveryEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_AUTO_POST_CONNECT_DISCOVERY, enabled)
    }

    fun hasCompletedOnce(): Boolean =
        MmkvManager.decodeSettingsBool(PREF_FIRST_CONNECT_DISCOVERY_DONE, false)

    fun maybeRunAfterFirstConnect(context: Context, guid: String) {
        if (!isAutoDiscoveryEnabled()) return
        if (hasCompletedOnce()) {
            _state.value = _state.value.copy(completedOnce = true)
            return
        }
        startSequentialDiscovery(context.applicationContext, guid, force = false)
    }

    fun startSequentialDiscovery(context: Context, guid: String, force: Boolean = true) {
        if (!force && activeJob?.isActive == true) return
        activeJob?.cancel()
        val appContext = context.applicationContext
        activeJob = scope.launch {
            LogUtil.i(TAG, "Starting sequential post-connect discovery for guid=$guid")
            var frontFound = 0
            var cfFound = 0
            try {
                for (provider in SmartConnectPolicy.postConnectDiscoveryOrder()) {
                    when (provider) {
                        CdnType.CLOUDFRONT -> {
                            _state.value = PostConnectDiscoveryState(
                                running = true,
                                activeProvider = CdnType.CLOUDFRONT,
                                cloudFrontFound = frontFound,
                                cloudflareFound = cfFound,
                                completedOnce = hasCompletedOnce(),
                            )
                            val found = awaitCloudFrontDiscovery(appContext, guid) { d, t, cidr ->
                                _state.value = _state.value.copy(
                                    running = true,
                                    activeProvider = CdnType.CLOUDFRONT,
                                    done = d,
                                    total = t,
                                    currentCidr = cidr,
                                )
                            }
                            frontFound = found.size
                            LogUtil.i(TAG, "Post-connect CloudFront discovery finished: ${found.size} ranges")
                        }

                        CdnType.CLOUDFLARE -> {
                            _state.value = _state.value.copy(
                                running = true,
                                activeProvider = CdnType.CLOUDFLARE,
                                done = 0,
                                total = 0,
                                currentCidr = "",
                                cloudFrontFound = frontFound,
                            )
                            val ispName = IspManager.detectIspName(appContext)
                            val found = awaitCloudflareDiscovery(appContext, ispName, guid) { d, t, cidr ->
                                _state.value = _state.value.copy(
                                    running = true,
                                    activeProvider = CdnType.CLOUDFLARE,
                                    done = d,
                                    total = t,
                                    currentCidr = cidr,
                                )
                            }
                            cfFound = found.size
                            LogUtil.i(TAG, "Post-connect Cloudflare discovery finished: ${found.size} ranges")
                        }

                        CdnType.UNKNOWN -> Unit
                    }
                }
                MmkvManager.encodeSettings(PREF_FIRST_CONNECT_DISCOVERY_DONE, true)
                _state.value = PostConnectDiscoveryState(
                    running = false,
                    activeProvider = null,
                    cloudFrontFound = frontFound,
                    cloudflareFound = cfFound,
                    completedOnce = true,
                )
            } catch (e: Exception) {
                LogUtil.e(TAG, "Post-connect discovery interrupted: ${e.message}", e)
                _state.value = _state.value.copy(running = false, activeProvider = null)
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        CloudFrontScanner.cancel()
        CloudflareScanner.cancel()
        _state.value = _state.value.copy(running = false, activeProvider = null)
    }

    private suspend fun awaitCloudFrontDiscovery(
        context: Context,
        guid: String,
        onProgress: (Int, Int, String) -> Unit,
    ): List<String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { CloudFrontScanner.cancel() }
        CloudFrontScanner.discoverGoodCidrs(
            context = context,
            guid = guid,
            onProgress = { done, total, cidr, _ -> onProgress(done, total, cidr) },
            onFinish = { result -> if (cont.isActive) cont.resume(result) }
        )
    }

    private suspend fun awaitCloudflareDiscovery(
        context: Context,
        ispName: String,
        guid: String,
        onProgress: (Int, Int, String) -> Unit,
    ): List<String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { CloudflareScanner.cancel() }
        CloudflareScanner.discoverGoodCidrs(
            context = context,
            ispName = ispName,
            guid = guid,
            onProgress = { done, total, cidr, _ -> onProgress(done, total, cidr) },
            onFinish = { result -> if (cont.isActive) cont.resume(result) }
        )
    }
}
