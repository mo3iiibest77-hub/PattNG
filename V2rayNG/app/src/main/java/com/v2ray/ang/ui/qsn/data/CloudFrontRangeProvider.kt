package com.v2ray.ang.ui.qsn.data

import android.content.Context
import com.v2ray.ang.ui.qsn.domain.CloudFrontIpRangesParser
import com.v2ray.ang.ui.qsn.domain.EdgeProvider
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Independent Amazon CloudFront IPv4 range provider.
 *
 * Fetches official AWS ranges from [CloudFrontIpRangesParser.OFFICIAL_IP_RANGES_URL]
 * (`service == "CLOUDFRONT"`), using [UnderlyingNetworkProvider] so fetches run
 * directly over the underlying physical network even when the VPN tunnel is active.
 */
object CloudFrontRangeProvider : RangeProvider {

    private const val TAG = "CloudFrontRangeProvider"

    sealed interface FetchResult {
        data class Success(val cidrs: List<String>, val syncedAt: Long) : FetchResult
        data class Failure(val reason: String) : FetchResult
    }

    override val provider: EdgeProvider = EdgeProvider.CLOUDFRONT

    override fun storedCidrs(): List<String> =
        ProviderRangeStore.snapshot(EdgeProvider.CLOUDFRONT).enabledScanCidrs

    suspend fun fetchOfficialRanges(context: Context): FetchResult = withContext(Dispatchers.IO) {
        try {
            val client = UnderlyingNetworkProvider.createDirectHttpClient(
                context = context,
                connectTimeoutSec = 15L,
                readTimeoutSec = 25L,
            )
            val request = Request.Builder()
                .url(CloudFrontIpRangesParser.OFFICIAL_IP_RANGES_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Failure("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val cidrs = CloudFrontIpRangesParser.parseIpv4Cidrs(body)
                if (cidrs.isEmpty()) {
                    return@withContext FetchResult.Failure("No CLOUDFRONT IPv4 prefixes found")
                }
                val now = System.currentTimeMillis()
                ProviderRangeStore.replaceFetched(EdgeProvider.CLOUDFRONT, cidrs)
                QsPrefs.markCloudFrontSynced(now)
                LogUtil.i(TAG, "Fetched " + cidrs.size + " official CloudFront IPv4 ranges")
                FetchResult.Success(cidrs, now)
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "CloudFront fetch failed: " + e.javaClass.simpleName)
            FetchResult.Failure(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }
}
