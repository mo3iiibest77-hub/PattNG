package com.v2ray.ang.ui.qsn3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaPulseNoticeParserTest {

    @Test
    fun parsesMandatoryPre244Notice_withSeveritySectionsAndCleanLinks() {
        val sample = """
            هشدار امنیتی و ارتقای اجباری (نسخه‌های پایین‌تر از 2.4.4)
            • دسترسی نسخه‌های قدیمی‌تر از 2.4.4 به‌زودی به‌طور کامل قطع خواهد شد.
            • لطفاً همین حالا دکمه «بروزرسانی» را بزنید و به نسخه 2.4.5 ارتقا دهید.

            پشتیبانی و دریافت نسخه رسمی:
            • کانال رسمی NOVAPULSE در تلگرام: @NovaPulseVPN
            • لینک مستقیم کانال: https://t.me/NovaPulseVPN
            • پشتیبانی مستقیم و دریافت/تمدید لایسنس: @Mo3i_Best
        """.trimIndent()

        val parsed = NovaPulseNoticeParser.parse(sample)

        assertEquals(NoticeSeverity.MANDATORY_SECURITY, parsed.severity)
        assertTrue(parsed.isRtl)
        assertEquals(2, parsed.sections.size)
        assertEquals(NoticeSectionKind.ALERT, parsed.sections[0].kind)
        assertEquals(NoticeSectionKind.SUPPORT, parsed.sections[1].kind)

        // Contact actions should deduplicate @NovaPulseVPN + https://t.me/NovaPulseVPN into one channel action + one support action.
        assertEquals(2, parsed.contactActions.size)
        assertEquals("@NovaPulseVPN", parsed.contactActions[0].subLabel)
        assertTrue(parsed.contactActions[0].isChannel)
        assertEquals("https://t.me/NovaPulseVPN", parsed.contactActions[0].target)
        assertEquals("@Mo3i_Best", parsed.contactActions[1].subLabel)
        assertFalse(parsed.contactActions[1].isChannel)
        assertEquals("https://t.me/Mo3i_Best", parsed.contactActions[1].target)
    }

    @Test
    fun parsesOptional244PlusNotice_asUpdateInfoNotMandatory() {
        val sample = """
            نسخه جدید 2.4.5 آماده دریافت است
            • وضعیت نسخه فعلی شما (2.4.4 و بالاتر): دسترسی کاملاً برقرار و ایمن است.
            • بروزرسانی به نسخه 2.4.5 در این مرحله اختیاری بوده و شامل بهبود رابط کاربری، امنیت و سرعت دریافت است.

            پشتیبانی و دریافت نسخه رسمی:
            • پشتیبانی: @Mo3i_Best
        """.trimIndent()

        val parsed = NovaPulseNoticeParser.parse(sample, isMandatoryHint = false)

        assertEquals(NoticeSeverity.UPDATE_INFO, parsed.severity)
        assertEquals(NoticeSectionKind.SAFE_STATUS, parsed.sections[0].kind)
        assertEquals(1, parsed.contactActions.size)
        assertEquals("@Mo3i_Best", parsed.contactActions[0].subLabel)
    }

    @Test
    fun linkTokenizer_stripsSurroundingPunctuationWithoutCorruptingTargets() {
        val line = "نسخه جدید را از (@NovaPulseVPN) یا https://t.me/NovaPulseVPN. دریافت کنید یا به @Mo3i_Best، پیام دهید."
        val segments = NovaPulseNoticeParser.tokenizeSegments(line)
        val links = segments.filterIsInstance<NoticeTextSegment.Link>()

        assertEquals(3, links.size)
        assertEquals("https://t.me/NovaPulseVPN", links[0].target)
        assertTrue(links[0].text.contains("@NovaPulseVPN"))
        assertFalse(links[0].text.contains(")"))

        assertEquals("https://t.me/NovaPulseVPN", links[1].target)
        assertFalse(links[1].text.contains("VPN."))
        assertEquals("https://t.me/NovaPulseVPN", links[1].text.removeSurrounding("\u2066", "\u2069"))

        assertEquals("https://t.me/Mo3i_Best", links[2].target)
        assertFalse(links[2].text.contains("،"))
    }

    @Test
    fun extractTelegramUsername_supportsAtHandleTmeUrlAndTgDeepLink() {
        assertEquals("Mo3i_Best", NovaPulseNoticeParser.extractTelegramUsername("@Mo3i_Best"))
        assertEquals("NovaPulseVPN", NovaPulseNoticeParser.extractTelegramUsername("https://t.me/NovaPulseVPN"))
        assertEquals("Mo3i_Best", NovaPulseNoticeParser.extractTelegramUsername("tg://resolve?domain=Mo3i_Best"))
        assertEquals(null, NovaPulseNoticeParser.extractTelegramUsername("https://nova.dontbedumb.ir/download"))
    }

    @Test
    fun dialogRoutingHelper_detectsRichMultiLineAndSupportNotices() {
        assertFalse(NovaPulseNoticeParser.shouldShowAsRichDialog("لایسنس فعال شد ✓"))
        assertTrue(NovaPulseNoticeParser.shouldShowAsRichDialog("خطا در اتصال؛ لطفاً به @Mo3i_Best پیام دهید."))
        assertTrue(NovaPulseNoticeParser.shouldShowAsRichDialog("خط اول\nخط دوم"))
    }
}
