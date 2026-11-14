package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.qsn.backend.LicenseStatus
import com.v2ray.ang.ui.qsn.backend.LocalDefaultLicenseProvider
import com.v2ray.ang.ui.qsn.backend.SubscriptionSyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendInterfacesTest {

    @Test
    fun localDefaultLicenseProviderReturnsUnconfiguredWithoutNetworkCalls() = runBlocking {
        val provider = LocalDefaultLicenseProvider()
        assertEquals(LicenseStatus.Unconfigured, provider.validate())
        assertEquals(LicenseStatus.Unconfigured, provider.validate("unused-key"))
    }

    @Test
    fun subscriptionOutcomeContractsRepresentUpdatedAndFailedCleanly() {
        val ok = SubscriptionSyncOutcome.Updated(configCount = 5)
        val err = SubscriptionSyncOutcome.Failed("HTTP 403")
        assertTrue(ok.configCount == 5)
        assertEquals("HTTP 403", err.reason)
    }
}
