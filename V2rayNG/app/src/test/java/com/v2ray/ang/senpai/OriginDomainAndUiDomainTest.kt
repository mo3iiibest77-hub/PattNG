package com.v2ray.ang.senpai

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class OriginDomainAndUiDomainTest {

    @Test
    fun resolveDomainFromProfile_prefersNonIpHostnameAcrossServerSniHost() {
        val withServerDomain = ProfileItem.create(EConfigType.VLESS).apply {
            server = "cdn.example.com"
            sni = "cdn.example.com"
            host = "host.example.com"
        }
        assertEquals("cdn.example.com", OriginDomainStore.resolveDomainFromProfile(withServerDomain))

        // After applyBestIp overwrites server with an IPv4, resolveDomainFromProfile recovers SNI/host.
        val rewrittenWithIp = ProfileItem.create(EConfigType.VLESS).apply {
            server = "104.16.10.22"
            sni = "edge-sni.example.net"
            host = "edge-host.example.net"
        }
        assertEquals("edge-sni.example.net", OriginDomainStore.resolveDomainFromProfile(rewrittenWithIp))
    }

    @Test
    fun cloudflareSeedRanges_validatesAndNormalizesIpv4Cidrs() {
        assertEquals("172.64.10.0/24", CloudflareSeedRanges.normalizeIpv4Cidr(" 172.64.10.99/24 "))
        assertEquals(null, CloudflareSeedRanges.normalizeIpv4Cidr("2606:4700::/32"))
        assertEquals(null, CloudflareSeedRanges.normalizeIpv4Cidr("not-a-cidr"))
    }
}
