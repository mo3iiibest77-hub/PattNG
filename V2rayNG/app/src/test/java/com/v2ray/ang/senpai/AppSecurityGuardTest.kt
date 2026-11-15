package com.v2ray.ang.senpai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSecurityGuardTest {

    @Test
    fun protectedEndpoints_decodeAccurately() {
        assertEquals("https://nova.dontbedumb.ir", AppSecurityGuard.protectedBackendBaseUrl())
        assertEquals(
            "https://nova.dontbedumb.ir/api/v1/license/app-update/latest",
            AppSecurityGuard.protectedUpdateServerUrl(),
        )
    }

    @Test
    fun releaseCertificateVerification_matchesExpectedAndRejectsTampered() {
        val valid = "A0:16:C9:BF:4D:8E:73:41:EF:D5:65:F7:FC:19:59:23:5F:CD:F7:4C:63:FE:B1:E3:1D:BA:EE:86:A9:C2:2A:E1"
        assertTrue(AppSecurityGuard.verifyCertDigestHex(valid))
        assertFalse(AppSecurityGuard.verifyCertDigestHex("0000000000000000000000000000000000000000000000000000000000000000"))
        assertFalse(AppSecurityGuard.verifyCertDigestHex(""))
        assertFalse(AppSecurityGuard.verifyCertDigestHex(null))
    }
}
