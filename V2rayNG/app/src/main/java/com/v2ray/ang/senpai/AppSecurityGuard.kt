package com.v2ray.ang.senpai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.v2ray.ang.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime integrity, anti-tamper, anti-debug, and protected endpoint decoding helper.
 *
 * Keeps sensitive endpoints and verification digests out of plain DEX constant pools and
 * blocks activation if a release build has been re-signed or patched debuggable.
 */
object AppSecurityGuard {

    private val XOR_KEY = intArrayOf(0x5A, 0xA7, 0x3C, 0x91, 0xD4, 0x1E, 0x67, 0xB2)

    private val BACKEND_URL_OBF = intArrayOf(
        0xF9, 0xB3, 0x4C, 0x2E, 0x8D, 0x3F, 0xFA, 0x96, 0x67, 0x10, 0xD6, 0xD7, 0x78,
        0xC9, 0xC2, 0x4F, 0xD5, 0xF5, 0x2D, 0x6A, 0xBB, 0xB8, 0x67, 0xA7, 0x30, 0x7D,
    )

    private val UPDATE_URL_OBF = intArrayOf(
        0xF9, 0xB3, 0x4C, 0x2E, 0x8D, 0x3F, 0xFA, 0x96, 0x67, 0x10, 0xD6, 0xD7, 0x78,
        0xC9, 0xC2, 0x4F, 0xD5, 0xF5, 0x2D, 0x6A, 0xBB, 0xB8, 0x67, 0xA7, 0x30, 0x7D,
        0xDF, 0x07, 0xD6, 0x14, 0xB2, 0x07, 0xC0, 0x88, 0xF4, 0x97, 0x7D, 0x80, 0x1B,
        0xAA, 0x0C, 0xF0, 0x61, 0x26, 0x86, 0x20, 0xB8, 0x31, 0x65, 0x16, 0xDC, 0xCB,
        0x41, 0xD9, 0xC4, 0x5D, 0xDC, 0x9C, 0x24,
    )

    private val RELEASE_CERT_OBF = intArrayOf(
        0xF0, 0xF7, 0x09, 0x68, 0x9D, 0x3C, 0xB7, 0xDF, 0x3D, 0x1B, 0x98, 0xD3, 0x61,
        0x9E, 0x99, 0x10, 0xC4, 0xF1, 0x2C, 0x3B, 0xF8, 0xE0, 0x63, 0xBE, 0x3F, 0x6C,
        0xC1, 0x5F, 0x93, 0x44, 0xAF, 0x42, 0xC4, 0xC1, 0xFB, 0x9A, 0x78, 0xD2, 0x41,
        0xBA, 0x5F, 0xEC, 0x66, 0x33, 0x94, 0x3C, 0xA8, 0x72, 0x30, 0x13, 0xCA, 0xCF,
        0x0B, 0xD0, 0x9D, 0x1F, 0xD8, 0xD6, 0x33, 0x34, 0xF4, 0xBC, 0x58, 0xA0,
    )

    private val tamperedFlag = AtomicBoolean(false)

    internal fun decodeObf(encoded: IntArray): String {
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            val mask = XOR_KEY[(i + 3) % XOR_KEY.size] xor ((i * 19) and 0xFF)
            out[i] = (encoded[i] xor mask).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    fun protectedBackendBaseUrl(): String = decodeObf(BACKEND_URL_OBF)

    fun protectedUpdateServerUrl(): String = decodeObf(UPDATE_URL_OBF)

    internal fun expectedReleaseCertSha256(): String = decodeObf(RELEASE_CERT_OBF)

    fun isRuntimeTampered(): Boolean = tamperedFlag.get()

    internal fun verifyCertDigestHex(actualHex: String?): Boolean {
        val normalized = actualHex?.trim()?.lowercase()?.replace(":", "") ?: return false
        return normalized.length == 64 && normalized == expectedReleaseCertSha256()
    }

    fun verifyAndRecordIntegrity(context: Context): Boolean {
        if (BuildConfig.DEBUG) {
            tamperedFlag.set(false)
            return true
        }
        val appDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debuggerAttached = runCatching { Debug.isDebuggerConnected() || Debug.waitingForDebugger() }.getOrDefault(false)
        val signatureValid = runCatching { checkPackageSigningCert(context) }.getOrDefault(false)
        val ok = !appDebuggable && !debuggerAttached && signatureValid
        tamperedFlag.set(!ok)
        return ok
    }

    @Suppress("DEPRECATION")
    private fun checkPackageSigningCert(context: Context): Boolean {
        val pm = context.packageManager
        val pkgName = context.packageName
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return false
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            info.signatures
        } ?: return false

        val digest = MessageDigest.getInstance("SHA-256")
        return certs.any { sig ->
            val hashBytes = digest.digest(sig.toByteArray())
            val hex = hashBytes.joinToString("") { "%02x".format(it) }
            verifyCertDigestHex(hex)
        }
    }
}
