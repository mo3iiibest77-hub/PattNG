package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
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

        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val targetFile = File(updatesDir, safeName)
        val tempFile = File(updatesDir, "$safeName.part")
        if (tempFile.exists()) tempFile.delete()

        val response = try {
            executeDownloadRequest(downloadUrl, useLocalProxy = false)
        } catch (directEx: Exception) {
            LogUtil.w(AppConfig.TAG, "Direct update download failed, trying active local proxy: ${directEx.message}")
            executeDownloadRequest(downloadUrl, useLocalProxy = true)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK response")
            val total = body.contentLength()
            var copied = 0L
            var lastReportedPct = -1

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            val pct = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
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

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        targetFile
    }

    private fun executeDownloadRequest(url: String, useLocalProxy: Boolean): okhttp3.Response {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
            .header("User-Agent", "NovaPulseUpdater")
            .get()
            .build()
        return client.newCall(req).execute()
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
