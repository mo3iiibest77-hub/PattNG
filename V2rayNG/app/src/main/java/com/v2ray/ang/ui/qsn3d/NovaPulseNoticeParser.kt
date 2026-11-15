package com.v2ray.ang.ui.qsn3d

import kotlin.math.min

enum class NoticeSeverity {
    MANDATORY_SECURITY,
    UPDATE_INFO,
    SECURITY_NOTICE,
    INFO,
}

enum class NoticeSectionKind {
    ALERT,
    SAFE_STATUS,
    SUPPORT,
    GENERAL,
}

enum class NoticeLinkKind {
    TELEGRAM_HANDLE,
    TELEGRAM_URL,
    WEB_URL,
}

sealed interface NoticeTextSegment {
    val text: String

    data class Plain(
        override val text: String,
        val isLtrIsolated: Boolean = false,
    ) : NoticeTextSegment

    data class Link(
        override val text: String,
        val target: String,
        val kind: NoticeLinkKind,
    ) : NoticeTextSegment
}

data class NoticeLine(
    val segments: List<NoticeTextSegment>,
    val isBullet: Boolean,
    val rawClean: String,
)

data class NoticeSection(
    val heading: String?,
    val kind: NoticeSectionKind,
    val lines: List<NoticeLine>,
)

data class NoticeContactAction(
    val label: String,
    val subLabel: String,
    val target: String,
    val isChannel: Boolean,
)

data class ParsedNoticeDocument(
    val severity: NoticeSeverity,
    val sections: List<NoticeSection>,
    val contactActions: List<NoticeContactAction>,
    val isRtl: Boolean,
)

/**
 * Pure-JVM structured notice parser & bidi/link tokenizer for NovaPulse update,
 * security, legacy-cutoff, and license notifications.
 */
object NovaPulseNoticeParser {

    private const val LRI = "\u2066"
    private const val PDI = "\u2069"

    private val LINK_REGEX = Regex(
        """(https?://[^\s\u200F\u200E<>()\[\]{}"']+)|(tg://[^\s\u200F\u200E<>()\[\]{}"']+)|((?<![A-Za-z0-9._%+-])@[A-Za-z][A-Za-z0-9_]{2,31})"""
    )
    private val VERSION_OR_CODE_REGEX = Regex(
        """\b(v?\d+\.\d+(?:\.\d+)?|NP-[A-Za-z0-9-]+)\b"""
    )
    private val LEADING_NOISE_REGEX = Regex(
        """^[\s•●▪▫▸►➤✓✅⚠️✨📢📩🔒🛡️🚀\-–—*]+"""
    )
    private val TRAILING_PUNCT_CHARS = setOf('.', ',', '،', '؛', ':', '!', '?', '؟', ')', ']', '}', '»', '"', '\'')

    fun isMostlyRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            val dir = Character.getDirectionality(ch)
            when (dir) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > 0 && rtl * 3 >= ltr
    }

    fun shouldShowAsRichDialog(message: String?): Boolean {
        val clean = message?.trim().orEmpty()
        if (clean.isEmpty()) return false
        if ('\n' in clean) return true
        if (LINK_REGEX.containsMatchIn(clean)) return true
        return clean.length > 135
    }

    fun parse(rawText: String, isMandatoryHint: Boolean = false): ParsedNoticeDocument {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n").trim()
        val isRtl = isMostlyRtl(normalized)
        if (normalized.isEmpty()) {
            return ParsedNoticeDocument(
                severity = if (isMandatoryHint) NoticeSeverity.MANDATORY_SECURITY else NoticeSeverity.INFO,
                sections = emptyList(),
                contactActions = emptyList(),
                isRtl = isRtl,
            )
        }

        val severity = inferSeverity(normalized, isMandatoryHint)
        val blocks = normalized.split(Regex("""\n\s*\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val sections = mutableListOf<NoticeSection>()
        val actionsMap = LinkedHashMap<String, NoticeContactAction>()

        for (block in blocks) {
            val rawLines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.isEmpty()) continue

            var heading: String? = null
            var bodyStartIndex = 0
            val firstLine = rawLines[0]
            val firstStartsAsBullet = startsWithBulletMarker(firstLine)
            val firstHasLink = LINK_REGEX.containsMatchIn(firstLine)

            if (!firstStartsAsBullet && !firstHasLink && (rawLines.size > 1 || firstLine.endsWith(":"))) {
                heading = stripLeadingMarkers(firstLine).trimEnd(':').trim()
                bodyStartIndex = 1
            }

            val parsedLines = mutableListOf<NoticeLine>()
            for (idx in bodyStartIndex until rawLines.size) {
                val rawLine = rawLines[idx]
                val isBullet = startsWithBulletMarker(rawLine) || rawLines.size > 1
                val cleanLine = stripLeadingMarkers(rawLine)
                if (cleanLine.isEmpty()) continue
                val segments = tokenizeSegments(cleanLine)
                collectContactActions(segments, actionsMap)
                parsedLines += NoticeLine(
                    segments = segments,
                    isBullet = isBullet,
                    rawClean = cleanLine,
                )
            }

            // If block only had heading (single line ending with ':'), promote it to body line
            if (parsedLines.isEmpty() && heading != null) {
                val segments = tokenizeSegments(heading)
                collectContactActions(segments, actionsMap)
                parsedLines += NoticeLine(segments = segments, isBullet = false, rawClean = heading)
                heading = null
            }

            val kind = inferSectionKind(heading, parsedLines)
            sections += NoticeSection(heading = heading, kind = kind, lines = parsedLines)
        }

        return ParsedNoticeDocument(
            severity = severity,
            sections = sections,
            contactActions = actionsMap.values.toList(),
            isRtl = isRtl,
        )
    }

    fun isolateLtrTokensForDisplay(text: String): String {
        if (text.isEmpty()) return text
        return VERSION_OR_CODE_REGEX.replace(text) { m -> "$LRI${m.value}$PDI" }
    }

    fun tokenizeSegments(line: String): List<NoticeTextSegment> {
        if (line.isEmpty()) return emptyList()
        val result = mutableListOf<NoticeTextSegment>()
        var cursor = 0

        for (match in LINK_REGEX.findAll(line)) {
            if (match.range.first > cursor) {
                appendPlainSegmented(line.substring(cursor, match.range.first), result)
            }

            var token = match.value
            var trailing = ""
            while (token.isNotEmpty() && token.last() in TRAILING_PUNCT_CHARS) {
                trailing = token.last() + trailing
                token = token.dropLast(1)
            }

            if (token.isNotEmpty()) {
                val kind = when {
                    token.startsWith("@") -> NoticeLinkKind.TELEGRAM_HANDLE
                    token.contains("t.me/", ignoreCase = true) ||
                        token.startsWith("tg://", ignoreCase = true) -> NoticeLinkKind.TELEGRAM_URL
                    else -> NoticeLinkKind.WEB_URL
                }
                val target = when (kind) {
                    NoticeLinkKind.TELEGRAM_HANDLE -> "https://t.me/${token.removePrefix("@")}"
                    NoticeLinkKind.TELEGRAM_URL -> token
                    NoticeLinkKind.WEB_URL -> token
                }
                result += NoticeTextSegment.Link(
                    text = "$LRI$token$PDI",
                    target = target,
                    kind = kind,
                )
            } else {
                appendPlainSegmented(match.value, result)
            }

            if (trailing.isNotEmpty()) {
                appendPlainSegmented(trailing, result)
            }
            cursor = min(line.length, match.range.last + 1)
        }

        if (cursor < line.length) {
            appendPlainSegmented(line.substring(cursor), result)
        }
        return result
    }

    private fun appendPlainSegmented(chunk: String, out: MutableList<NoticeTextSegment>) {
        if (chunk.isEmpty()) return
        var cursor = 0
        for (m in VERSION_OR_CODE_REGEX.findAll(chunk)) {
            if (m.range.first > cursor) {
                out += NoticeTextSegment.Plain(chunk.substring(cursor, m.range.first), isLtrIsolated = false)
            }
            out += NoticeTextSegment.Plain("$LRI${m.value}$PDI", isLtrIsolated = true)
            cursor = m.range.last + 1
        }
        if (cursor < chunk.length) {
            out += NoticeTextSegment.Plain(chunk.substring(cursor), isLtrIsolated = false)
        }
    }

    private fun collectContactActions(
        segments: List<NoticeTextSegment>,
        actionsMap: LinkedHashMap<String, NoticeContactAction>,
    ) {
        for (seg in segments) {
            if (seg !is NoticeTextSegment.Link) continue
            val rawVisible = seg.text.removeSurrounding(LRI, PDI).trim()
            val telegramUser = extractTelegramUsername(seg.target)
            if (telegramUser != null) {
                val key = "tg:${telegramUser.lowercase()}"
                if (!actionsMap.containsKey(key)) {
                    val isChannel = telegramUser.equals("NovaPulseVPN", ignoreCase = true)
                    val label = if (isChannel) "کانال رسمی" else "پشتیبانی تلگرام"
                    actionsMap[key] = NoticeContactAction(
                        label = label,
                        subLabel = "@$telegramUser",
                        target = "https://t.me/$telegramUser",
                        isChannel = isChannel,
                    )
                }
            } else if (seg.kind == NoticeLinkKind.WEB_URL) {
                val key = "web:${seg.target.lowercase()}"
                if (!actionsMap.containsKey(key)) {
                    actionsMap[key] = NoticeContactAction(
                        label = "باز کردن لینک",
                        subLabel = rawVisible.take(28),
                        target = seg.target,
                        isChannel = false,
                    )
                }
            }
        }
    }

    fun extractTelegramUsername(target: String): String? {
        val clean = target.trim()
        if (clean.startsWith("@")) {
            return clean.removePrefix("@").takeIf { it.isNotBlank() }
        }
        val tmeMatch = Regex("""https?://(?:www\.)?t\.me/([A-Za-z][A-Za-z0-9_]{2,31})""", RegexOption.IGNORE_CASE).find(clean)
        if (tmeMatch != null) return tmeMatch.groupValues[1]
        val tgMatch = Regex("""tg://resolve\?domain=([A-Za-z][A-Za-z0-9_]{2,31})""", RegexOption.IGNORE_CASE).find(clean)
        if (tgMatch != null) return tgMatch.groupValues[1]
        return null
    }

    private fun startsWithBulletMarker(line: String): Boolean {
        return line.startsWith("•") ||
            line.startsWith("- ") ||
            line.startsWith("* ") ||
            line.startsWith("●") ||
            line.startsWith("✅") ||
            line.startsWith("⚠️") ||
            line.startsWith("📢") ||
            line.startsWith("📩")
    }

    private fun stripLeadingMarkers(line: String): String {
        return line.replaceFirst(LEADING_NOISE_REGEX, "").trim()
    }

    private fun inferSeverity(text: String, isMandatoryHint: Boolean): NoticeSeverity {
        if (isMandatoryHint) return NoticeSeverity.MANDATORY_SECURITY
        val hasCutoff = text.contains("قطع خواهد شد") ||
            text.contains("ارتقای اجباری") ||
            text.contains("اجباری است")
        val hasOptionalMarker = text.contains("اختیاری است") && text.contains("برقرار و ایمن است")
        if (hasCutoff && !hasOptionalMarker) return NoticeSeverity.MANDATORY_SECURITY
        if (text.contains("هشدار") ||
            text.contains("منقضی") ||
            text.contains("غیرفعال") ||
            text.contains("لغو") ||
            text.contains("مسدود") ||
            text.contains("revoked", ignoreCase = true)
        ) {
            return NoticeSeverity.SECURITY_NOTICE
        }
        if (text.contains("نسخه جدید") || text.contains("بروزرسانی")) {
            return NoticeSeverity.UPDATE_INFO
        }
        return NoticeSeverity.INFO
    }

    private fun inferSectionKind(heading: String?, lines: List<NoticeLine>): NoticeSectionKind {
        val combined = buildString {
            if (!heading.isNullOrBlank()) append(heading).append('\n')
            lines.forEach { append(it.rawClean).append('\n') }
        }
        return when {
            combined.contains("پشتیبانی") ||
                combined.contains("کانال رسمی") ||
                combined.contains("@") ||
                combined.contains("t.me/") -> NoticeSectionKind.SUPPORT
            combined.contains("قطع خواهد شد") ||
                combined.contains("اجباری") ||
                combined.contains("هشدار") -> NoticeSectionKind.ALERT
            combined.contains("کاملاً برقرار و ایمن") ||
                combined.contains("2.4.4 و بالاتر") ||
                combined.contains("اختیاری است") -> NoticeSectionKind.SAFE_STATUS
            else -> NoticeSectionKind.GENERAL
        }
    }
}
