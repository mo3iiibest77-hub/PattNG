package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal data class RealTrafficSpeedResult(
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

internal object RealTrafficSpeedTest {

    private const val TEST_SIZE_BYTES = 8L * 1024L * 1024L
    private const val MIN_UPLOAD_BYTES_PER_SECOND = 700L * 1024L

    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=$TEST_SIZE_BYTES"

    suspend fun run(configJson: String): RealTrafficSpeedResult? = withContext(Dispatchers.IO) {
        val port = Random.nextInt(20000, 50000)

        val json = JsonUtil.parseString(configJson) ?: return@withContext null

        val inbounds = com.google.gson.JsonArray()
        val inbound = com.google.gson.JsonObject()

        inbound.addProperty("tag", "pattng-speedtest")
        inbound.addProperty("port", port)
        inbound.addProperty("protocol", "socks")

        val settings = com.google.gson.JsonObject()
        settings.addProperty("auth", "noauth")
        settings.addProperty("udp", false)
        settings.addProperty("userLevel", 8)
        inbound.add("settings", settings)

        val sniffing = com.google.gson.JsonObject()
        sniffing.addProperty("enabled", false)
        inbound.add("sniffing", sniffing)

        inbounds.add(inbound)
        json.add("inbounds", inbounds)

        val controller: CoreController =
            CoreNativeManager.newCoreController(
                object : CoreCallbackHandler {
                    override fun startup(): Long = 0
                    override fun shutdown(): Long = 0
                    override fun onEmitStatus(code: Long, message: String?): Long = 0
                }
            )

        try {
            CoreNativeManager.initCoreEnv(null)
            controller.startLoop(JsonUtil.toJsonPretty(json) ?: return@withContext null, 0)

            if (!controller.isRunning) {
                LogUtil.w(AppConfig.TAG, "RealTrafficSpeedTest: Xray controller failed to start")
                return@withContext null
            }

            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(AppConfig.LOOPBACK, port)
            )

            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()

            // ── UPLOAD ──────────────────────────────────────────────────────
            val uploadStart = System.nanoTime()

            val uploadBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = TEST_SIZE_BYTES
                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(1024 * 1024)
                    var remaining = TEST_SIZE_BYTES
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        sink.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }

            val uploadRequest = Request.Builder()
                .url(UPLOAD_URL)
                .post(uploadBody)
                .header("Cache-Control", "no-cache")
                .build()

            val uploadResponse = client.newCall(uploadRequest).execute()
            if (!uploadResponse.isSuccessful) {
                uploadResponse.close()
                LogUtil.w(AppConfig.TAG, "RealTrafficSpeedTest: upload HTTP ${uploadResponse.code}")
                return@withContext null
            }
            uploadResponse.close()

            val uploadElapsed = (System.nanoTime() - uploadStart).coerceAtLeast(1L)
            val uploadBytesPerSecond = TEST_SIZE_BYTES * 1_000_000_000L / uploadElapsed

            if (uploadBytesPerSecond < MIN_UPLOAD_BYTES_PER_SECOND) {
                LogUtil.w(
                    AppConfig.TAG,
                    "RealTrafficSpeedTest: upload too slow: ${uploadBytesPerSecond / 1024} KB/s"
                )
                return@withContext null
            }

            // ── DOWNLOAD ─────────────────────────────────────────────────────
            val downloadRequest = Request.Builder()
                .url(DOWNLOAD_URL)
                .get()
                .header("Cache-Control", "no-cache")
                .build()

            val downloadStart = System.nanoTime()
            val downloadResponse = client.newCall(downloadRequest).execute()

            if (!downloadResponse.isSuccessful) {
                downloadResponse.close()
                LogUtil.w(AppConfig.TAG, "RealTrafficSpeedTest: download HTTP ${downloadResponse.code}")
                return@withContext null
            }

            var downloaded = 0L
            downloadResponse.body?.byteStream()?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    downloaded += count
                }
            }
            downloadResponse.close()

            val downloadElapsed = (System.nanoTime() - downloadStart).coerceAtLeast(1L)
            val downloadBytesPerSecond = downloaded * 1_000_000_000L / downloadElapsed

            LogUtil.i(
                AppConfig.TAG,
                "RealTrafficSpeedTest: upload=${uploadBytesPerSecond / 1024}KB/s " +
                    "download=${downloadBytesPerSecond / 1024}KB/s"
            )

            RealTrafficSpeedResult(
                uploadBytesPerSecond = uploadBytesPerSecond,
                downloadBytesPerSecond = downloadBytesPerSecond,
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "RealTrafficSpeedTest failed", e)
            null
        } finally {
            try {
                if (controller.isRunning) controller.stopLoop()
            } catch (_: Throwable) {}
        }
    }
}
