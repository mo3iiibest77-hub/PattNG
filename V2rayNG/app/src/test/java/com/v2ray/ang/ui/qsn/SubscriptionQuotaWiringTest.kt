package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription-quota path, checked as source: the Subscription-Userinfo
 * header is parsed, persisted onto the subscription, and surfaced to the panel
 * through a usage percentage label. These properties live behind the real
 * fetch + MMKV round-trip, so the assertions read the files directly.
 */
class SubscriptionQuotaWiringTest {

    private fun source(relative: String): String = SourceFiles.main(relative)

    @Test
    fun theFetchKeepsTheUserInfoHeaderAlongsideTheContent() {
        val body = SourceFiles.body(source("handler/AngConfigManager.kt"), "fun updateConfigViaSub(it: SubscriptionCache)")
        assertTrue(body.contains("var subUserInfo: String? = null"))
        assertTrue(body.contains("subUserInfo = result.userInfo"))
    }

    @Test
    fun theQuotaIsParsedAndPersistedOntoTheSubscription() {
        val body = SourceFiles.body(source("handler/AngConfigManager.kt"), "fun updateConfigViaSub(it: SubscriptionCache)")
        assertTrue(body.contains("val quota = SubscriptionQuota.parse(subUserInfo)"))
        assertTrue(body.contains("it.subscription.upload = quota.upload"))
        assertTrue(body.contains("it.subscription.download = quota.download"))
        assertTrue(body.contains("it.subscription.total = quota.total"))
        assertTrue(body.contains("it.subscription.expire = quota.expire"))
        assertTrue(body.contains("MmkvManager.encodeSubscription(it.guid, it.subscription)"))
    }

    @Test
    fun theRowExposesAUsagePercentageDerivedFromTheQuota() {
        val sub = source("ui/qsn/domain/Subscriptions.kt")
        assertTrue(sub.contains("SubscriptionQuota.usagePercent(uploadBytes, downloadBytes, totalBytes)"))
    }

    @Test
    fun thePanelShowsTheUsagePercentageWithTheLocalizedLabel() {
        val screen = source("ui/qsn/QsConfigScreen.kt")
        assertTrue(screen.contains("row.usagePercent?.let"))
        assertTrue(screen.contains("R.string.qs_sub_usage"))
    }
}