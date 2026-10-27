package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The EN/FA control must be reversible and must persist a concrete choice. */
class LanguageToggleTest {

    @Test
    fun `english switches to persian`() {
        assertEquals("fa", LanguageToggle.next("en"))
    }

    @Test
    fun `persian switches back to english`() {
        assertEquals("en", LanguageToggle.next("fa"))
    }

    @Test
    fun `toggling twice returns to the start`() {
        assertEquals("en", LanguageToggle.next(LanguageToggle.next("en")))
    }

    @Test
    fun `a stored choice wins over the system language`() {
        assertEquals("fa", LanguageToggle.normalize("fa", "en"))
        assertEquals("en", LanguageToggle.normalize("en", "fa"))
    }

    @Test
    fun `auto falls back to the system language`() {
        assertEquals("fa", LanguageToggle.normalize("auto", "fa"))
        assertEquals("en", LanguageToggle.normalize(null, "en"))
        assertEquals("en", LanguageToggle.normalize("", "de"))
    }
}
