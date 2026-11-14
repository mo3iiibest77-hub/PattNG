package com.v2ray.ang.senpai

import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Subscription traffic quota, validity dates, and device binding summary.
 */
data class SubscriptionQuotaInfo(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireAtSeconds: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val planName: String? = null,
    val createdAtSeconds: Long = 0L,
    val maxDevices: Int = 1,
    val usedDevices: Int = 1,
    val licenseStatus: String = "active",
) {
    val usedBytes: Long get() = (uploadBytes + downloadBytes).coerceAtLeast(0L)
    val remainingBytes: Long? get() = if (totalBytes > 0L) (totalBytes - usedBytes).coerceAtLeast(0L) else null
    val usageFraction: Float?
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    val usagePercent: Int?
        get() = usageFraction?.let { (it * 100f).toInt().coerceIn(0, 100) }
}

/**
 * Parses standard Xray/V2Ray `subscription-userinfo` headers such as
 * `upload=123; download=456; total=10737418240; expire=1799999999`.
 */
object SubscriptionQuotaParser {

    fun parseHeader(header: String?): SubscriptionQuotaInfo? {
        if (header.isNullOrBlank()) return null
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var matchedAny = false

        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            when (key) {
                "upload" -> { upload = value.coerceAtLeast(0L); matchedAny = true }
                "download" -> { download = value.coerceAtLeast(0L); matchedAny = true }
                "total" -> { total = value.coerceAtLeast(0L); matchedAny = true }
                "expire" -> { expire = value; matchedAny = true }
            }
        }
        return if (matchedAny) SubscriptionQuotaInfo(upload, download, total, expire) else null
    }
}

object SubscriptionQuotaStore {

    private const val KEY_PREFIX = "qsn_sub_quota_"
    private val gson = Gson()

    fun save(subId: String, info: SubscriptionQuotaInfo) {
        MmkvManager.encodeSettings(KEY_PREFIX + subId, gson.toJson(info))
    }

    fun get(subId: String): SubscriptionQuotaInfo? {
        val raw = MmkvManager.decodeSettingsString(KEY_PREFIX + subId) ?: return null
        return runCatching { gson.fromJson(raw, SubscriptionQuotaInfo::class.java) }.getOrNull()
    }

    fun fetchAndSaveHeader(subId: String, url: String, userAgent: String? = null) {
        val existing = get(subId)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        runCatching {
            val reqBuilder = Request.Builder().url(url).get()
            if (!userAgent.isNullOrBlank()) {
                reqBuilder.header("User-Agent", userAgent)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val rawHeader = resp.header("subscription-userinfo")
                    ?: resp.header("Subscription-Userinfo")
                val createdHeader = resp.header("X-Subscription-Created-At")?.toLongOrNull() ?: 0L
                SubscriptionQuotaParser.parseHeader(rawHeader)?.let { info ->
                    save(
                        subId,
                        info.copy(
                            planName = existing?.planName,
                            createdAtSeconds = createdHeader.takeIf { it > 0L } ?: (existing?.createdAtSeconds ?: 0L),
                            maxDevices = existing?.maxDevices ?: 1,
                            usedDevices = existing?.usedDevices ?: 1,
                            licenseStatus = existing?.licenseStatus ?: "active",
                        )
                    )
                }
            }
        }
    }
}
