package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.senpai.AppSecurityGuard
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class ApkInstallResult {
    data object LaunchedInstaller : ApkInstallResult()
    data class NeedsUnknownSourcesPermission(val apkFile: File) : ApkInstallResult()
    data class Failed(val reason: String) : ApkInstallResult()
}

object NovaPulseApkDownloader {

    fun sanitizeApkFileName(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }

    fun buildDownloadCandidates(
        downloadUrl: String,
        fileName: String,
        serverLatestUrl: String = AppConfig.APP_UPDATE_SERVER_URL,
    ): List<String> {
        val safeName = sanitizeApkFileName(fileName)
        val gatewayBase = serverLatestUrl.trim().removeSuffix("/latest").trimEnd('/')
        val gatewayUrl = if (gatewayBase.isNotEmpty()) "$gatewayBase/files/$safeName" else ""
        return linkedSetOf(gatewayUrl, downloadUrl.trim())
            .filter { it.startsWith("https://", ignoreCase = true) }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        expectedSha256: String = "",
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile && old.name.endsWith(".apk", ignoreCase = true)) {
                old.delete()
            }
        }

        val safeName = sanitizeApkFileName(fileName)
        val targetFile = File(updatesDir, safeName)
        val tempFile = File(updatesDir, "$safeName.part")
        if (tempFile.exists()) tempFile.delete()

        val candidates = buildDownloadCandidates(downloadUrl, safeName)
        val response = executeWithMirrorFallback(candidates)

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK response")
            val total = body.contentLength()
            var copied = 0L
            var lastReportedPct = 0
            onProgress(1)

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        val pct = if (total > 0L) {
                            ((copied * 100L) / total).toInt().coerceIn(1, 99)
                        } else {
                            (1 + (copied / (320L * 1024L)).toInt()).coerceIn(1, 95)
                        }
                        if (pct > lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct)
                        }
                    }
                    output.flush()
                }
            }
        }

        if (!isValidApkFile(tempFile)) {
            tempFile.delete()
            throw IllegalStateException("Downloaded file is not a valid APK package")
        }

        if (expectedSha256.isNotBlank()) {
            val actualSha = computeSha256(tempFile)
            if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                tempFile.delete()
                throw IllegalStateException("APK integrity checksum mismatch")
            }
        }

        if (!BuildConfig.DEBUG && !verifyApkSigningCertificate(context, tempFile)) {
            tempFile.delete()
            throw IllegalStateException("APK signing certificate verification failed")
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        targetFile
    }

    private fun executeWithMirrorFallback(candidates: List<String>): okhttp3.Response {
        var lastError: Exception? = null
        val activeProxyPort = runCatching { SettingsManager.getHttpPort() }.getOrDefault(0)
        val proxyModes = if (activeProxyPort > 0) listOf(false, true) else listOf(false)

        for (candidate in candidates) {
            for (useProxy in proxyModes) {
                try {
                    val resp = executeDownloadRequest(candidate, useLocalProxy = useProxy)
                    if (resp.isSuccessful) {
                        return resp
                    }
                    val code = resp.code
                    resp.close()
                    lastError = IllegalStateException("HTTP $code")
                    if (code == 404 || code == 410) {
                        // Do not waste time retrying local proxy on definitive 404/410; try next mirror.
                        break
                    }
                } catch (ex: Exception) {
                    LogUtil.w(
                        AppConfig.TAG,
                        "Update download attempt failed (url=$candidate proxy=$useProxy): ${ex.message}"
                    )
                    lastError = ex
                }
            }
        }
        throw (lastError ?: IllegalStateException("No working update download mirror"))
    }

    private fun executeDownloadRequest(url: String, useLocalProxy: Boolean): okhttp3.Response {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (useLocalProxy) {
            val port = SettingsManager.getHttpPort()
            if (port > 0) {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
            }
        }

        val client = builder.build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "NovaPulseUpdater/2.4.5")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        return client.newCall(req).execute()
    }

    internal fun certificateSha256Hex(certBytes: ByteArray): String? {
        if (certBytes.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(certBytes).joinToString("") { "%02x".format(it) }
    }

    internal fun verifySigningCertificateDigests(
        hasMultipleSigners: Boolean,
        apkContentsSignerDigests: List<String>,
        signingCertificateHistoryDigests: List<String>?,
    ): Boolean {
        if (apkContentsSignerDigests.isEmpty()) return false
        if (apkContentsSignerDigests.any { it.isBlank() }) return false
        if (hasMultipleSigners) {
            // Multi-signer APKs do not use certificate rotation history; validate only active co-signers.
            return apkContentsSignerDigests.any { AppSecurityGuard.verifyCertDigestHex(it) }
        }
        val history = signingCertificateHistoryDigests
        if (history != null) {
            if (history.isEmpty() || history.any { it.isBlank() }) return false
            return history.any { AppSecurityGuard.verifyCertDigestHex(it) }
        }
        return apkContentsSignerDigests.any { AppSecurityGuard.verifyCertDigestHex(it) }
    }

    internal fun verifySigningCertificates(
        hasMultipleSigners: Boolean,
        apkContentsSigners: List<ByteArray>,
        signingCertificateHistory: List<ByteArray>?,
    ): Boolean {
        if (apkContentsSigners.isEmpty()) return false
        val activeDigests = ArrayList<String>(apkContentsSigners.size)
        for (cert in apkContentsSigners) {
            val hex = certificateSha256Hex(cert) ?: return false
            activeDigests.add(hex)
        }
        val historyDigests = if (hasMultipleSigners) {
            null
        } else {
            signingCertificateHistory?.let { history ->
                if (history.isEmpty()) return false
                val out = ArrayList<String>(history.size)
                for (cert in history) {
                    val hex = certificateSha256Hex(cert) ?: return false
                    out.add(hex)
                }
                out
            }
        }
        return verifySigningCertificateDigests(
            hasMultipleSigners = hasMultipleSigners,
            apkContentsSignerDigests = activeDigests,
            signingCertificateHistoryDigests = historyDigests,
        )
    }

    internal fun verifyLegacySignatureDigests(signatureDigests: List<String>?): Boolean {
        if (signatureDigests.isNullOrEmpty()) return false
        if (signatureDigests.any { it.isBlank() }) return false
        return signatureDigests.any { AppSecurityGuard.verifyCertDigestHex(it) }
    }

    internal fun verifyLegacySignatures(signatures: List<ByteArray>?): Boolean {
        if (signatures.isNullOrEmpty()) return false
        val digests = ArrayList<String>(signatures.size)
        for (sig in signatures) {
            val hex = certificateSha256Hex(sig) ?: return false
            digests.add(hex)
        }
        return verifyLegacySignatureDigests(digests)
    }

    @Suppress("DEPRECATION")
    fun verifyApkSigningCertificate(context: Context, apkFile: File): Boolean {
        return runCatching {
            if (!apkFile.isFile) return@runCatching false
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: return@runCatching false
                val signingInfo = pkgInfo.signingInfo ?: return@runCatching false
                val apkSigners = signingInfo.apkContentsSigners
                    ?.map { it.toByteArray() }
                    ?: return@runCatching false
                val history = signingInfo.signingCertificateHistory
                    ?.map { it.toByteArray() }
                verifySigningCertificates(
                    hasMultipleSigners = signingInfo.hasMultipleSigners(),
                    apkContentsSigners = apkSigners,
                    signingCertificateHistory = history,
                )
            } else {
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES,
                ) ?: return@runCatching false
                val legacySigners = pkgInfo.signatures?.map { it.toByteArray() }
                verifyLegacySignatures(legacySigners)
            }
        }.getOrDefault(false)
    }

    fun isValidApkFile(file: File): Boolean {
        if (!file.isFile || file.length() < 64_000L) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                fis.read(magic) == 4 &&
                    magic[0] == 0x50.toByte() &&
                    magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() &&
                    magic[3] == 0x04.toByte()
            }
        }.getOrDefault(false)
    }

    fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun launchApkInstaller(context: Context, apkFile: File): ApkInstallResult {
        if (!apkFile.isFile) {
            return ApkInstallResult.Failed("APK file missing")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return ApkInstallResult.NeedsUnknownSourcesPermission(apkFile)
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ApkInstallResult.LaunchedInstaller
        }.getOrElse { err ->
            LogUtil.e(AppConfig.TAG, "Failed to launch APK installer", err)
            ApkInstallResult.Failed(err.message ?: "Installer launch failed")
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
