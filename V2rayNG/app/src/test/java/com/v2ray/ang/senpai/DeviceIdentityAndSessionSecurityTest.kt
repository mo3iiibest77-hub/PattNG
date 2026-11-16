package com.v2ray.ang.senpai

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceIdentityAndSessionSecurityTest {

    private class JvmCryptoProvider : DeviceCryptoProvider {
        private val aesKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val ecPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        private val rng = SecureRandom()

        override fun encryptString(plaintext: String): String {
            if (plaintext.isEmpty()) return ""
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val enc = Base64.getEncoder()
            return "v1:${enc.encodeToString(iv)}:${enc.encodeToString(ct)}"
        }

        override fun decryptString(encoded: String): String? {
            if (!encoded.startsWith("v1:")) return null
            return runCatching {
                val payload = encoded.removePrefix("v1:")
                val sep = payload.indexOf(':')
                val dec = Base64.getDecoder()
                val iv = dec.decode(payload.substring(0, sep))
                val ct = dec.decode(payload.substring(sep + 1))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }.getOrNull()
        }

        override fun getOrCreateSigningPublicKeySpkiB64(): String =
            Base64.getEncoder().encodeToString(ecPair.public.encoded)

        override fun signProofMessage(message: ByteArray): String {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(ecPair.private)
            sig.update(message)
            return Base64.getEncoder().encodeToString(sig.sign())
        }
    }

    private class InMemoryPrefsStorage : DeviceIdentityManager.KeyValueStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun clear() {
            map.clear()
        }
    }

    private class InMemorySessionBackingStore : LicenseSessionStore.BackingStore {
        val strs = mutableMapOf<String, String>()
        val bools = mutableMapOf<String, Boolean>()
        val longs = mutableMapOf<String, Long>()
        override fun getString(key: String, default: String): String = strs[key] ?: default
        override fun putString(key: String, value: String) {
            strs[key] = value
        }
        override fun getBool(key: String, default: Boolean): Boolean = bools[key] ?: default
        override fun putBool(key: String, value: Boolean) {
            bools[key] = value
        }
        override fun getLong(key: String, default: Long): Long = longs[key] ?: default
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private lateinit var cryptoA: JvmCryptoProvider
    private lateinit var storageA: InMemoryPrefsStorage
    private lateinit var sessStore: InMemorySessionBackingStore

    @Before
    fun setUp() {
        cryptoA = JvmCryptoProvider()
        storageA = InMemoryPrefsStorage()
        sessStore = InMemorySessionBackingStore()
        DeviceIdentityManager.configureForTest(
            crypto = cryptoA,
            storage = storageA,
            androidIdAux = "deadbeef12345678",
            metadata = DeviceMetadata("Google Pixel 8", "Google", "Pixel 8", "google", "15", 35, "2.4.2"),
        )
        LicenseSessionStore.configureForTest(sessStore, cryptoA)
    }

    @After
    fun tearDown() {
        DeviceIdentityManager.resetForTest()
        LicenseSessionStore.resetForTest()
    }

    @Test
    fun deviceIdentity_isUniquePersistentNonLegacy_andProducesVerifiableEcdsaProof() {
        val id1 = DeviceIdentityManager.getDeviceId()
        val id2 = DeviceIdentityManager.getDeviceId()
        assertEquals(64, id1.length)
        assertEquals(id1, id2)
        assertNotEquals(DeviceIdentityManager.LEGACY_SHARED_ANDROID_ID, id1)
        // Stored values must be AES-GCM sealed (never plaintext)
        assertFalse(storageA.map.values.any { it == id1 })

        // Distinct installation must produce a distinct device ID even with same androidIdAux
        val storageB = InMemoryPrefsStorage()
        val cryptoB = JvmCryptoProvider()
        DeviceIdentityManager.configureForTest(
            crypto = cryptoB,
            storage = storageB,
            androidIdAux = "deadbeef12345678",
        )
        val idOtherInstall = DeviceIdentityManager.getDeviceId()
        assertNotEquals(id1, idOtherInstall)

        // ECDSA P-256 signature proof verification
        val nonce = "nonce_test_12345"
        val pubB64 = DeviceIdentityManager.getPublicKeySpkiB64()
        val sigB64 = DeviceIdentityManager.signChallenge(nonce, "redeem")
        val pubBytes = Base64.getDecoder().decode(pubB64)
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pubKey)
        verifier.update("v1|$nonce|$idOtherInstall|redeem".toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(Base64.getDecoder().decode(sigB64)))
    }

    @Test
    fun sessionStore_encryptsSecrets_supportsRotation_andPendingLogout() {
        LicenseSessionStore.saveSession(
            licenseKey = "NP-TEST-ABCD-1234",
            sessionId = "sess_123",
            sessionToken = "nps_super_secret_token_999",
            subscriptionUrl = "https://nova.dontbedumb.ir/api/v1/license/sub",
            expiresAtSec = (System.currentTimeMillis() / 1000L) + 3600L,
        )
        assertTrue(LicenseSessionStore.hasActiveSession())
        // Backing store must NOT contain raw session_token or raw license key
        val rawDump = sessStore.strs.values.joinToString(" ")
        assertFalse(rawDump.contains("nps_super_secret_token_999"))
        assertFalse(rawDump.contains("NP-TEST-ABCD-1234"))

        val snap = LicenseSessionStore.getSnapshot()
        assertEquals("NP-TEST-ABCD-1234", snap.licenseKey)
        assertEquals("nps_super_secret_token_999", snap.sessionToken)

        LicenseSessionStore.updateRotatedToken("nps_rotated_token_000", (System.currentTimeMillis() / 1000L) + 7200L)
        assertEquals("nps_rotated_token_000", LicenseSessionStore.getSnapshot().sessionToken)

        LicenseSessionStore.enqueuePendingLogout("nps_rotated_token_000", "dev_xyz")
        val pending = LicenseSessionStore.getPendingLogout()
        assertNotNull(pending)
        assertEquals("nps_rotated_token_000", pending?.sessionToken)
        LicenseSessionStore.clearPendingLogout()
        assertNull(LicenseSessionStore.getPendingLogout())

        LicenseSessionStore.clearActiveSession()
        assertFalse(LicenseSessionStore.hasActiveSession())
    }

    @Test
    fun backendClient_redeemUsesChallengeProof_andSubscriptionUsesHeaderAuthWithoutTokenInUrl() {
        val capturedRedeemBodies = mutableListOf<String>()
        val mockHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            val bodyStr = buf.readUtf8()
            val path = req.url.encodedPath
            when {
                path.endsWith("/challenge") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"nonce":"srv_nonce_777","expires_in":120}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                path.endsWith("/redeem") -> {
                    capturedRedeemBodies.add(bodyStr)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                            {
                              "status":"ok",
                              "license_key":"NP-ABCD-EFGH-1234",
                              "session_id":"sess_01",
                              "session_token":"nps_live_secret_tok",
                              "session_expires_at": 1999999999,
                              "subscription_url":"https://nova.dontbedumb.ir/api/v1/license/sub",
                              "plan_name":"Pro",
                              "max_devices":2,
                              "used_devices":1,
                              "total_bytes":10737418240
                            }
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                path.endsWith("/logout") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"ok":true,"status":"logged_out","used_devices":0}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> throw IOException("Unexpected path $path")
            }
        }.build()

        val result = LicenseBackendClient.redeem(
            rawInput = "NP-ABCD-EFGH-1234",
            remarksHint = "NovaPulse",
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = mockHttp,
        )
        assertTrue(
            "Expected ReadySubscription but got $result",
            result is LicenseResolutionResult.ReadySubscription,
        )
        val ready = result as LicenseResolutionResult.ReadySubscription
        assertEquals("https://nova.dontbedumb.ir/api/v1/license/sub", ready.subscriptionUrl)
        assertFalse(ready.subscriptionUrl.contains("nps_live_secret_tok"))
        assertFalse(ready.subscriptionUrl.contains("device_id"))

        val redeemedJson = JsonParser.parseString(capturedRedeemBodies.single()).asJsonObject
        assertEquals("srv_nonce_777", redeemedJson.get("challenge_nonce").asString)
        assertTrue(redeemedJson.get("public_key").asString.isNotBlank())
        assertTrue(redeemedJson.get("device_signature").asString.isNotBlank())

        val authHdrs = LicenseBackendClient.buildSubscriptionAuthHeaders()
        assertEquals("Bearer nps_live_secret_tok", authHdrs["Authorization"])
        assertEquals(DeviceIdentityManager.getDeviceId(), authHdrs["X-Device-Id"])

        val logoutOut = LicenseBackendClient.logoutFromServer(
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = mockHttp,
        )
        assertTrue(logoutOut is LogoutOutcome.Success)
    }

    @Test
    fun deviceSlotFingerprint_requiresHighEntropyAndroidId_andIgnoresLowEntropyOemIds() {
        val fpA = DeviceIdentityManager.getDeviceSlotFingerprint()
        assertEquals(64, fpA.length)

        // Separate install with new storage & keypair on the exact same phone retains slot fp
        DeviceIdentityManager.configureForTest(
            crypto = JvmCryptoProvider(),
            storage = InMemoryPrefsStorage(),
            androidIdAux = "deadbeef12345678",
            metadata = DeviceMetadata("Google Pixel 8", "Google", "Pixel 8", "google", "15", 35, "2.4.5"),
        )
        val fpB = DeviceIdentityManager.getDeviceSlotFingerprint()
        assertEquals(fpA, fpB)

        for (weakId in listOf("9774d56d682e549c", "0000000000000000", "1111111111111111", "short12", "")) {
            DeviceIdentityManager.configureForTest(
                crypto = cryptoA,
                storage = storageA,
                androidIdAux = weakId,
                metadata = DeviceMetadata("Generic", "Brand", "ModelX", "brand", "14", 34, "2.4.5"),
            )
            assertEquals("Weak android_id '$weakId' must not produce slot fp", "", DeviceIdentityManager.getDeviceSlotFingerprint())
        }
    }

    @Test
    fun refreshSessionToken_enforcesSingleFlightLease_abortsBadChallenge_andMarksTransportFailure() {
        LicenseSessionStore.saveSession(
            licenseKey = "NP-TEST-LEAS-0001",
            sessionId = "sess_lease_1",
            sessionToken = "tok_lease_1",
            subscriptionUrl = "https://nova.dontbedumb.ir/api/v1/license/sub",
            expiresAtSec = 1999999999L,
        )

        val owner = LicenseSessionStore.tryAcquireRefreshLease()
        assertNotNull(owner)
        assertNull("Concurrent acquire must fail while lease is active", LicenseSessionStore.tryAcquireRefreshLease())
        LicenseSessionStore.releaseRefreshLease(owner)
        val owner2 = LicenseSessionStore.tryAcquireRefreshLease()
        assertNotNull(owner2)
        LicenseSessionStore.releaseRefreshLease(owner2)

        // Failed /challenge must abort before hitting /refresh
        var refreshCalledOnBadChallenge = false
        val badChallengeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.endsWith("/refresh")) refreshCalledOnBadChallenge = true
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Err")
                .body("""{"status":"server_error"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        assertFalse(
            LicenseBackendClient.refreshSessionToken(
                backendBaseUrlOverride = "https://nova.dontbedumb.ir",
                client = badChallengeHttp,
            )
        )
        assertFalse(refreshCalledOnBadChallenge)

        // IOException during /refresh must mark transport failure
        val dropRefreshHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.endsWith("/challenge")) {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"nonce":"n_drop_1","expires_in":120}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                throw IOException("Connection reset during /refresh")
            }
        }.build()
        assertFalse(
            LicenseBackendClient.refreshSessionToken(
                backendBaseUrlOverride = "https://nova.dontbedumb.ir",
                client = dropRefreshHttp,
            )
        )
        assertTrue(LicenseSessionStore.hasRecentRefreshTransportFailure())
    }

    @Test
    fun refreshStatus_recoversConcurrentRotationAndRefreshDrop_butRejectsRevokedSession() {
        // 1. Concurrent rotation in another process (tok_old -> tok_new)
        LicenseSessionStore.saveSession(
            licenseKey = "NP-TEST-RECV-0001",
            sessionId = "sess_rot_1",
            sessionToken = "tok_old",
            subscriptionUrl = "https://nova.dontbedumb.ir/api/v1/license/sub",
            expiresAtSec = 1999999999L,
        )
        val rotHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val auth = req.header("Authorization").orEmpty()
            if (auth == "Bearer tok_old") {
                LicenseSessionStore.updateRotatedToken("tok_new", 1999999999L)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"status":"session_invalid","message":"Invalid token"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                assertEquals("Bearer tok_new", auth)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok","subscription_url":"https://nova.dontbedumb.ir/api/v1/license/sub","session_expires_at":1999999999}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }.build()
        val rotRes = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-TEST-RECV-0001",
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = rotHttp,
        )
        assertTrue("Expected recovery via newer MMKV token, got $rotRes", rotRes is LicenseResolutionResult.ReadySubscription)

        // 2. Dropped /refresh response -> recover once via ECDSA redeem
        LicenseSessionStore.saveSession(
            licenseKey = "NP-TEST-RECV-0001",
            sessionId = "sess_drop_1",
            sessionToken = "tok_stale",
            subscriptionUrl = "https://nova.dontbedumb.ir/api/v1/license/sub",
            expiresAtSec = 1999999999L,
        )
        LicenseSessionStore.markRefreshTransportFailure()
        var redeemCalls = 0
        val dropRecoverHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            when {
                path.endsWith("/status") -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"status":"session_invalid","message":"Invalid token"}""".toResponseBody("application/json".toMediaType()))
                    .build()
                path.endsWith("/challenge") -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"nonce":"n_rec_1","expires_in":120}""".toResponseBody("application/json".toMediaType()))
                    .build()
                path.endsWith("/redeem") -> {
                    redeemCalls += 1
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"status":"ok","license_key":"NP-TEST-RECV-0001","session_id":"sess_drop_2","session_token":"tok_recovered","session_expires_at":1999999999,"subscription_url":"https://nova.dontbedumb.ir/api/v1/license/sub"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> throw IOException("Unexpected $path")
            }
        }.build()
        val dropRes = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-TEST-RECV-0001",
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = dropRecoverHttp,
        )
        assertTrue("Expected silent ECDSA redeem recovery, got $dropRes", dropRes is LicenseResolutionResult.ReadySubscription)
        assertEquals(1, redeemCalls)
        assertFalse(LicenseSessionStore.hasRecentRefreshTransportFailure())
        assertEquals("tok_recovered", LicenseSessionStore.getSnapshot().sessionToken)

        // 3. Explicit session_revoked MUST NEVER auto-redeem even if transport flag exists
        LicenseSessionStore.markRefreshTransportFailure()
        var redeemOnRevokeCalls = 0
        val revokedHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            if (req.url.encodedPath.endsWith("/redeem")) redeemOnRevokeCalls += 1
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"status":"session_revoked","message":"Revoked by admin"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val revRes = LicenseBackendClient.refreshStatus(
            licenseKey = "NP-TEST-RECV-0001",
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = revokedHttp,
        )
        assertTrue(revRes is LicenseResolutionResult.Failure && revRes.isDefinitiveRejection)
        assertEquals(0, redeemOnRevokeCalls)
    }

    @Test
    fun logoutFromServerWithRetry_retriesTransientFailures_andStopsOnAlreadyInvalid() = runBlocking {
        LicenseSessionStore.saveSession(
            licenseKey = "NP-TEST-LOUT-0001",
            sessionId = "sess_lout_1",
            sessionToken = "tok_lout_1",
            subscriptionUrl = "https://nova.dontbedumb.ir/api/v1/license/sub",
            expiresAtSec = 1999999999L,
        )
        var attempts = 0
        val flakyHttp = OkHttpClient.Builder().addInterceptor { chain ->
            attempts += 1
            if (attempts < 3) throw IOException("Transient handover loss")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"ok":true,"status":"logged_out","used_devices":0}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val out = LicenseBackendClient.logoutFromServerWithRetry(
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = flakyHttp,
            maxAttempts = 3,
            retryDelayBaseMs = 1L,
        )
        assertTrue(out is LogoutOutcome.Success)
        assertEquals(3, attempts)

        var invalidAttempts = 0
        val invalidHttp = OkHttpClient.Builder().addInterceptor { chain ->
            invalidAttempts += 1
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"status":"session_logged_out"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val invalidOut = LicenseBackendClient.logoutFromServerWithRetry(
            backendBaseUrlOverride = "https://nova.dontbedumb.ir",
            client = invalidHttp,
            maxAttempts = 3,
            retryDelayBaseMs = 1L,
        )
        assertTrue(invalidOut is LogoutOutcome.AlreadyInvalid)
        assertEquals(1, invalidAttempts)
    }
}
