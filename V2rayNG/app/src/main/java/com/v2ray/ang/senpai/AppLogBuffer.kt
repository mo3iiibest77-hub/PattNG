package com.v2ray.ang.senpai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppLogLevel(val shortLabel: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERR"),
    CORE("CORE"),
}

data class AppLogEntry(
    val id: Long,
    val timestampMs: Long,
    val timeText: String,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
)

/**
 * Thread-safe in-app diagnostic log ring buffer with strict secret redaction.
 * Ensures license keys, session tokens, Bearer headers, UUIDs, and raw proxy links
 * can never appear in the in-app log viewer or copied diagnostic reports.
 */
object AppLogBuffer {

    private const val MAX_ENTRIES = 350
    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES + 8)
    private var nextId = 1L

    private val _logsFlow = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<AppLogEntry>> = _logsFlow.asStateFlow()

    private val LICENSE_KEY_REGEX = Regex("""\bNP-[A-Z0-9]{2,8}(?:-[A-Z0-9]{2,8})+\b""", RegexOption.IGNORE_CASE)
    private val SESSION_TOKEN_REGEX = Regex("""\bnps_[A-Za-z0-9._~=-]{6,}\b""")
    private val AUTH_HEADER_REGEX = Regex(
        """(?i)\b(authorization|x-license-key|x-session-token|bearer)\s*[:=]?\s*(?:bearer\s+)?[A-Za-z0-9._~+/=-]{4,}"""
    )
    private val RAW_CONFIG_URI_REGEX = Regex(
        """(?i)\b(vless|vmess|trojan|ss|ssr|socks|socks5|http|wireguard|wg|hysteria2|hy2|tuic)://\S+"""
    )
    private val QUERY_SECRET_REGEX = Regex("""([?&](?:token|key|secret|auth|session|sig|signature)=)[^&\s]+""", RegexOption.IGNORE_CASE)
    private val UUID_REGEX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val SUB_PATH_TOKEN_REGEX = Regex("""(/by-sub/|/api/v1/license/sub\S*/)[A-Za-z0-9._~=-]+""", RegexOption.IGNORE_CASE)

    fun redact(raw: String): String {
        if (raw.isBlank()) return ""
        var out = raw
        out = RAW_CONFIG_URI_REGEX.replace(out) { m -> "${m.groupValues[1]}://[CONFIG_REDACTED]" }
        out = LICENSE_KEY_REGEX.replace(out, "[LICENSE_REDACTED]")
        out = SESSION_TOKEN_REGEX.replace(out, "[TOKEN_REDACTED]")
        out = AUTH_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]} [REDACTED]" }
        out = QUERY_SECRET_REGEX.replace(out) { m -> "${m.groupValues[1]}[REDACTED]" }
        out = SUB_PATH_TOKEN_REGEX.replace(out) { m -> "${m.groupValues[1]}[REDACTED]" }
        out = UUID_REGEX.replace(out, "[UUID_REDACTED]")
        return out
    }

    fun record(level: AppLogLevel, tag: String, message: String) {
        val cleanTag = redact(tag).ifBlank { "NovaPulse" }.take(32)
        val cleanMsg = redact(message).trim()
        if (cleanMsg.isEmpty()) return
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val snapshot: List<AppLogEntry>
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(
                AppLogEntry(
                    id = nextId++,
                    timestampMs = now,
                    timeText = timeFmt,
                    level = level,
                    tag = cleanTag,
                    message = cleanMsg,
                )
            )
            snapshot = entries.toList()
        }
        _logsFlow.value = snapshot
    }

    fun info(tag: String, message: String) = record(AppLogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = record(AppLogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = record(AppLogLevel.ERROR, tag, message)
    fun core(tag: String, message: String) = record(AppLogLevel.CORE, tag, message)

    fun snapshot(): List<AppLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        _logsFlow.value = emptyList()
    }

    /**
     * Reads recent Xray/core & NovaPulse lines from Android's logcat ring buffer (non-blocking `-d`)
     * for the diagnostics viewer, redacting all secrets before returning.
     */
    fun readSanitizedSystemCoreLines(maxLines: Int = 180): List<AppLogEntry> {
        val out = mutableListOf<AppLogEntry>()
        val proc = runCatching {
            ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "time",
                "-t",
                maxLines.coerceIn(40, 400).toString(),
                "GoLog:I",
                "v2ray:I",
                "libv2ray:I",
                "com.quietstorm.ng:I",
                "*:W",
            )
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return emptyList()

        return try {
            val lines = proc.inputStream.bufferedReader().use { it.readLines() }
            var seq = 10_000_000L
            for (rawLine in lines) {
                if (rawLine.startsWith("---------")) continue
                val relevant = rawLine.contains("GoLog", ignoreCase = true) ||
                    rawLine.contains("libv2ray", ignoreCase = true) ||
                    rawLine.contains("v2ray", ignoreCase = true) ||
                    rawLine.contains("quietstorm", ignoreCase = true) ||
                    rawLine.contains("SmartConnect", ignoreCase = true) ||
                    rawLine.contains("Cloudflare", ignoreCase = true) ||
                    rawLine.contains("CloudFront", ignoreCase = true) ||
                    rawLine.contains("Failover", ignoreCase = true) ||
                    rawLine.contains("License", ignoreCase = true)
                if (!relevant) continue

                val cleanLine = redact(rawLine).trim()
                if (cleanLine.isEmpty()) continue
                val lvl = when {
                    rawLine.contains(" E/") || rawLine.contains(" F/") -> AppLogLevel.ERROR
                    rawLine.contains(" W/") -> AppLogLevel.WARN
                    rawLine.contains("GoLog") || rawLine.contains("v2ray", ignoreCase = true) -> AppLogLevel.CORE
                    else -> AppLogLevel.INFO
                }
                val timePart = if (cleanLine.length >= 18 && cleanLine[2] == '-' && cleanLine[8] == ':') {
                    cleanLine.substring(6, 14)
                } else {
                    "--:--:--"
                }
                out.add(
                    AppLogEntry(
                        id = seq++,
                        timestampMs = 0L,
                        timeText = timePart,
                        level = lvl,
                        tag = if (lvl == AppLogLevel.CORE) "XrayCore" else "System",
                        message = cleanLine,
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { proc.destroy() }
        }
    }

    fun formatExportText(entriesToExport: List<AppLogEntry>): String {
        if (entriesToExport.isEmpty()) return ""
        return entriesToExport.joinToString("\n") { entry ->
            "[${entry.timeText}] [${entry.level.shortLabel}] [${entry.tag}] ${redact(entry.message)}"
        }
    }
}
