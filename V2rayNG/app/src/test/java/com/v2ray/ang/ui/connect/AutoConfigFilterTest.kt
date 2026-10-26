package com.v2ray.ang.ui.connect

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AutoConfigFilter].
 *
 * Each test exercises a single eligibility criterion in isolation so a
 * regression in any one rule is immediately identifiable.
 */
class AutoConfigFilterTest {

    @Test
    fun `empty input returns empty list`() {
        val result = AutoConfigFilter.filterEligible(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ws vless on 443 with tls and uuid is eligible`() {
        val config = eligibleVless()
        assertTrue(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `xhttp vmess on 2053 with tls is eligible`() {
        val config = eligibleVless().apply {
            network = "xhttp"
            serverPort = "2053"
        }
        assertTrue(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `tcp network is not eligible`() {
        val config = eligibleVless().apply { network = "tcp" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `grpc network is not eligible`() {
        val config = eligibleVless().apply { network = "grpc" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `none security is not eligible`() {
        val config = eligibleVless().apply { security = "" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `non-https port 80 is not eligible`() {
        val config = eligibleVless().apply { serverPort = "80" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `http-only cf port 2082 is not eligible without tls`() {
        val config = eligibleVless().apply {
            security = ""
            serverPort = "2082"
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `all cf https ports are eligible`() {
        for (port in AutoConfigFilter.HTTPS_PORTS) {
            val config = eligibleVless().apply { serverPort = port }
            assertTrue("Port $port should be eligible", AutoConfigFilter.isEligible(config))
        }
    }

    @Test
    fun `blank server is not eligible`() {
        val config = eligibleVless().apply { server = "" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `null server is not eligible`() {
        val config = eligibleVless().apply { server = null }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `vless without uuid is not eligible`() {
        val config = eligibleVless().apply { password = "" }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `trojan without password is not eligible`() {
        val config = eligibleVless().apply {
            configType = EConfigType.TROJAN
            password = null
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `shadowsocks without method is not eligible`() {
        val config = eligibleVless().apply {
            configType = EConfigType.SHADOWSOCKS
            method = null
            password = "pass"
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `custom config type is not eligible`() {
        val config = eligibleVless().apply { configType = EConfigType.CUSTOM }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `filterEligible preserves order and excludes ineligible`() {
        val good1 = eligibleVless()
        val bad = eligibleVless().apply { network = "tcp" }
        val good2 = eligibleVless().apply { serverPort = "8443" }

        val result = AutoConfigFilter.filterEligible(listOf(good1, bad, good2))
        assertEquals(2, result.size)
        assertEquals(good1, result[0])
        assertEquals(good2, result[1])
    }

    @Test
    fun `wireguard without public key is not eligible`() {
        val config = eligibleVless().apply {
            configType = EConfigType.WIREGUARD
            publicKey = null
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `hysteria2 without password is not eligible`() {
        val config = eligibleVless().apply {
            configType = EConfigType.HYSTERIA2
            password = null
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    @Test
    fun `http proxy without username is not eligible`() {
        val config = eligibleVless().apply {
            configType = EConfigType.HTTP
            username = null
        }
        assertFalse(AutoConfigFilter.isEligible(config))
    }

    private fun eligibleVless() = ProfileItem.create(EConfigType.VLESS).apply {
        server = "example.com"
        serverPort = "443"
        network = "ws"
        security = AppConfig.TLS
        password = "uuid-1234"
        sni = "example.com"
        host = "example.com"
        path = "/ws"
    }
}
