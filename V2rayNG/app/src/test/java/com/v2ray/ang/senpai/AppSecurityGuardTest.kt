package com.v2ray.ang.senpai

import com.v2ray.ang.handler.NovaPulseApkDownloader
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

    @Test
    fun apkDownloader_enforcesHttpsOnlyMirrors() {
        val candidates = NovaPulseApkDownloader.buildDownloadCandidates(
            downloadUrl = "http://insecure.example.com/app.apk",
            fileName = "NovaPulse-v2.4.5.apk",
            serverLatestUrl = "https://nova.dontbedumb.ir/api/v1/license/app-update/latest",
        )
        assertEquals(
            listOf("https://nova.dontbedumb.ir/api/v1/license/app-update/files/NovaPulse-v2.4.5.apk"),
            candidates,
        )
    }

    @Test
    fun apkDownloader_acceptsCurrentAndRotatedCertsWhileRejectingUnknownMultiOrIncomplete() {
        val validReleaseDigest = "A016C9BF4D8E7341EFD565F7FC1959235FCDF74C63FEB1E31DBAEE86A9C22AE1"
        val unknownDigest = "1111111111111111111111111111111111111111111111111111111111111111"
        val rotatedLeafDigest = "2222222222222222222222222222222222222222222222222222222222222222"

        // 1. Current official Release certificate is accepted (with history, without history, and legacy < API 28).
        assertTrue(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(validReleaseDigest),
                signingCertificateHistoryDigests = listOf(validReleaseDigest),
            ),
        )
        assertTrue(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(validReleaseDigest),
                signingCertificateHistoryDigests = null,
            ),
        )
        assertTrue(
            NovaPulseApkDownloader.verifyLegacySignatureDigests(listOf(validReleaseDigest)),
        )

        // 2. Unknown/tampered certificate is rejected across active, rotated, and legacy paths.
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(unknownDigest),
                signingCertificateHistoryDigests = listOf(unknownDigest),
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(rotatedLeafDigest),
                signingCertificateHistoryDigests = listOf(unknownDigest, rotatedLeafDigest),
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifyLegacySignatureDigests(listOf(unknownDigest)),
        )

        // 3. Rotated single-signer APK with valid historical lineage is accepted.
        assertTrue(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(rotatedLeafDigest),
                signingCertificateHistoryDigests = listOf(validReleaseDigest, rotatedLeafDigest),
            ),
        )

        // 4. Multi-signer APK checks active apkContentsSigners policy and ignores history.
        assertTrue(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = true,
                apkContentsSignerDigests = listOf(validReleaseDigest, unknownDigest),
                signingCertificateHistoryDigests = null,
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = true,
                apkContentsSignerDigests = listOf(unknownDigest, rotatedLeafDigest),
                signingCertificateHistoryDigests = listOf(validReleaseDigest, rotatedLeafDigest),
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificates(
                hasMultipleSigners = true,
                apkContentsSigners = listOf("unknown-signer".toByteArray(Charsets.US_ASCII)),
                signingCertificateHistory = emptyList(),
            ),
        )

        // 5. Incomplete, empty, blank, or malformed signature metadata is rejected (fail closed).
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = emptyList(),
                signingCertificateHistoryDigests = listOf(validReleaseDigest),
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(validReleaseDigest),
                signingCertificateHistoryDigests = emptyList(),
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(validReleaseDigest, ""),
                signingCertificateHistoryDigests = null,
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificateDigests(
                hasMultipleSigners = false,
                apkContentsSignerDigests = listOf(rotatedLeafDigest),
                signingCertificateHistoryDigests = listOf(validReleaseDigest, " "),
            ),
        )
        assertFalse(NovaPulseApkDownloader.verifyLegacySignatureDigests(null))
        assertFalse(NovaPulseApkDownloader.verifyLegacySignatureDigests(emptyList()))
        assertFalse(NovaPulseApkDownloader.verifyLegacySignatureDigests(listOf("")))
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificates(
                hasMultipleSigners = false,
                apkContentsSigners = emptyList(),
                signingCertificateHistory = null,
            ),
        )
        assertFalse(
            NovaPulseApkDownloader.verifySigningCertificates(
                hasMultipleSigners = false,
                apkContentsSigners = listOf(ByteArray(0)),
                signingCertificateHistory = null,
            ),
        )
        assertFalse(NovaPulseApkDownloader.verifyLegacySignatures(listOf(ByteArray(0))))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NovaPulseApkDownloader.certificateSha256Hex("abc".toByteArray(Charsets.US_ASCII)),
        )
    }
}
