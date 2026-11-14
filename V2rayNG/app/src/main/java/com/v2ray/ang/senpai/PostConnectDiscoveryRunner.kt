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
    val completedCloudflareOnce: Boolean = false,
    val completedCloudFrontOnce: Boolean = false,
) {
    val completedOnce: Boolean get() = completedCloudflareOnce
}

/**
 * Executes one-time-forever ISP Discovery over the physical uplink ONLY for the CDN provider(s)
 * actually used in the user's configurations.
 *
 * Guarantees:
 * - Never runs CloudFront Discovery when the user only has Cloudflare configs.
 * - Never re-runs automatic Discovery after completion on reconnects.
 * - Exposes [isRunning] so 2-hour background optimization never overlaps active Discovery.
 */
object PostConnectDiscoveryRunner {

    private const val TAG = "PostConnectDiscovery"
    private const val PREF_CF_DISCOVERY_FOREVER_DONE = "np_cf_discovery_forever_done"
    private const val PREF_CFR_DISCOVERY_FOREVER_DONE = "np_cfr_discovery_forever_done"
    private const val PREF_AUTO_POST_CONNECT_DISCOVERY = "qsn_auto_post_connect_discovery"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(PostConnectDiscoveryState())
    val state: StateFlow<PostConnectDiscoveryState> = _state.asStateFlow()

    fun isRunning(): Boolean = activeJob?.isActive == true || _state.value.running

    fun isAutoDiscoveryEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(PREF_AUTO_POST_CONNECT_DISCOVERY, true)

    fun setAutoDiscoveryEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_AUTO_POST_CONNECT_DISCOVERY, enabled)
    }

    fun hasCompletedForever(provider: CdnType): Boolean = when (provider) {
        CdnType.CLOUDFLARE -> MmkvManager.decodeSettingsBool(PREF_CF_DISCOVERY_FOREVER_DONE, false)
        CdnType.CLOUDFRONT -> MmkvManager.decodeSettingsBool(PREF_CFR_DISCOVERY_FOREVER_DONE, false)
        CdnType.UNKNOWN -> true
    }

    fun markCompletedForever(provider: CdnType, done: Boolean = true) {
        when (provider) {
            CdnType.CLOUDFLARE -> MmkvManager.encodeSettings(PREF_CF_DISCOVERY_FOREVER_DONE, done)
            CdnType.CLOUDFRONT -> MmkvManager.encodeSettings(PREF_CFR_DISCOVERY_FOREVER_DONE, done)
            CdnType.UNKNOWN -> Unit
        }
    }

    fun maybeRunAfterFirstConnect(
        context: Context,
        guid: String,
        activeProvider: CdnType,
        subscriptionProviders: Collection<CdnType> = emptyList(),
    ) {
        val cfDone = hasCompletedForever(CdnType.CLOUDFLARE)
        val cfrDone = hasCompletedForever(CdnType.CLOUDFRONT)
        _state.value = _state.value.copy(
            completedCloudflareOnce = cfDone,
            completedCloudFrontOnce = cfrDone,
        )
        if (!isAutoDiscoveryEnabled()) return

        val required = SmartConnectPolicy.determineRequiredDiscoveryProviders(
            detectedProvidersInSubscription = subscriptionProviders,
            activeProvider = activeProvider,
        ).filter { !hasCompletedForever(it) }

        if (required.isEmpty()) return

        startProvidersDiscovery(
            context = context.applicationContext,
            guid = guid,
            providers = required,
            force = false,
        )
    }

    fun startSequentialDiscovery(
        context: Context,
        guid: String,
        providers: List<CdnType> = listOf(CdnType.CLOUDFLARE),
        force: Boolean = true,
    ) {
        startProvidersDiscovery(context, guid, providers, force)
    }

    private fun startProvidersDiscovery(
        context: Context,
        guid: String,
        providers: List<CdnType>,
        force: Boolean,
    ) {
        if (!force && activeJob?.isActive == true) return
        val targets = providers.filter { it == CdnType.CLOUDFLARE || it == CdnType.CLOUDFRONT }.distinct()
        if (targets.isEmpty()) return

        activeJob?.cancel()
        val appContext = context.applicationContext
        activeJob = scope.launch {
            LogUtil.i(TAG, "Starting post-connect discovery for providers=$targets guid=$guid")
            var frontFound = 0
            var cfFound = 0
            try {
                for (provider in targets) {
                    when (provider) {
                        CdnType.CLOUDFRONT -> {
                            _state.value = PostConnectDiscoveryState(
                                running = true,
                                activeProvider = CdnType.CLOUDFRONT,
                                cloudFrontFound = frontFound,
                                cloudflareFound = cfFound,
                                completedCloudflareOnce = hasCompletedForever(CdnType.CLOUDFLARE),
                                completedCloudFrontOnce = hasCompletedForever(CdnType.CLOUDFRONT),
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
                            markCompletedForever(CdnType.CLOUDFRONT, true)
                            LogUtil.i(TAG, "One-time CloudFront discovery finished: ${found.size} ranges")
                        }

                        CdnType.CLOUDFLARE -> {
                            _state.value = _state.value.copy(
                                running = true,
                                activeProvider = CdnType.CLOUDFLARE,
                                done = 0,
                                total = 0,
                                currentCidr = "",
                                cloudFrontFound = frontFound,
                                cloudflareFound = cfFound,
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
                            markCompletedForever(CdnType.CLOUDFLARE, true)
                            LogUtil.i(TAG, "One-time Cloudflare discovery finished: ${found.size} ranges")
                        }

                        CdnType.UNKNOWN -> Unit
                    }
                }
                _state.value = PostConnectDiscoveryState(
                    running = false,
                    activeProvider = null,
                    cloudFrontFound = frontFound,
                    cloudflareFound = cfFound,
                    completedCloudflareOnce = hasCompletedForever(CdnType.CLOUDFLARE),
                    completedCloudFrontOnce = hasCompletedForever(CdnType.CLOUDFRONT),
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
