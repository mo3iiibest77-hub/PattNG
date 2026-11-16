package com.v2ray.ang.core

import android.util.Base64
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.util.JsonUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.net.URLEncoder
import java.util.Base64 as JavaBase64

class CoreOutboundBuilderDualPipelineTest {

    private lateinit var mockLog: MockedStatic<Log>
    private lateinit var mockBase64: MockedStatic<Base64>
    private var fragmentPrefEnabled: Boolean = false

    @Before
    fun setUp() {
        fragmentPrefEnabled = false
        mockLog = mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<ByteArray> {
            Base64.decode(Mockito.anyString(), Mockito.anyInt())
        }.thenAnswer { inv ->
            val input = inv.arguments[0] as String
            val flags = inv.arguments[1] as Int
            val decoder = if ((flags and Base64.URL_SAFE) != 0) JavaBase64.getUrlDecoder() else JavaBase64.getDecoder()
            decoder.decode(input)
        }
        mockBase64.`when`<String> {
            Base64.encodeToString(Mockito.any(ByteArray::class.java), Mockito.anyInt())
        }.thenAnswer { inv ->
            val input = inv.arguments[0] as ByteArray
            val flags = inv.arguments[1] as Int
            var enc = if ((flags and Base64.URL_SAFE) != 0) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
            if ((flags and Base64.NO_PADDING) != 0) enc = enc.withoutPadding()
            enc.encodeToString(input)
        }

        CoreOutboundBuilder.settingsProvider = object : OutboundSettingsProvider {
            override fun getBool(key: String, default: Boolean): Boolean {
                return if (key == AppConfig.PREF_FRAGMENT_ENABLED) fragmentPrefEnabled else default
            }

            override fun getString(key: String, default: String?): String? = default
        }
    }

    @After
    fun tearDown() {
        CoreOutboundBuilder.settingsProvider = MmkvOutboundSettingsProvider
        mockBase64.close()
        mockLog.close()
    }

    @Test
    fun xhttpReality_preservesPqAndChromeFpWithoutCdnOverrides() {
        val samplePqv = "Mldsa65SamplePublicKeyValueForRealityXhttp"
        val extraJson = URLEncoder.encode("""{"xPaddingObfsMode":true,"xmux":{"maxConcurrency":"16-32"}}""", "UTF-8")
        val uri = "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:8445" +
            "?type=xhttp&encryption=none&path=%2Fai-articles&host=net.example.ir&mode=auto" +
            "&extra=$extraJson&security=reality&pbk=PubKey12345&fp=chrome&sni=www.nvidia.com" +
            "&sid=abcd&spx=%2F&pqv=$samplePqv#XHTTP-Reality"

        val parsed = VlessFmt.parse(uri)
        assertNotNull(parsed)
        fragmentPrefEnabled = true // Ensure global fragment pref cannot corrupt REALITY

        val outbound = CoreOutboundBuilder.convert(parsed!!)
        assertNotNull(outbound)
        val stream = outbound!!.streamSettings!!

        assertEquals("xhttp", stream.network)
        assertEquals(AppConfig.REALITY, stream.security)
        assertNull(stream.tlsSettings)
        val reality = stream.realitySettings
        assertNotNull(reality)
        assertEquals("chrome", reality?.fingerprint)
        assertNull("REALITY must NOT receive CDN cipherSuites", reality?.cipherSuites)
        assertEquals("www.nvidia.com", reality?.serverName)
        assertEquals("PubKey12345", reality?.publicKey)
        assertEquals("abcd", reality?.shortId)
        assertEquals(samplePqv, reality?.mldsa65Verify)
        assertNull("REALITY must NOT receive CDN fragment finalmask", stream.finalmask)
    }

    @Test
    fun tcpReality_withoutFpDefaultsToChromeNotUnsafeAndNoFinalMask() {
        val uri = "vless://22222222-2222-2222-2222-222222222222@203.0.113.10:8447" +
            "?type=tcp&security=reality&pbk=PubKeyTcp&sni=www.nvidia.com&sid=ef01#TCP-Reality"

        val parsed = VlessFmt.parse(uri)!!
        // Simulate accidental "unsafe" value before outbound build
        parsed.fingerPrint = "unsafe"
        val outbound = CoreOutboundBuilder.convert(parsed)!!
        val stream = outbound.streamSettings!!

        assertEquals("tcp", stream.network)
        assertEquals(AppConfig.REALITY, stream.security)
        assertEquals("chrome", stream.realitySettings?.fingerprint)
        assertNull(stream.realitySettings?.cipherSuites)
        assertNull(stream.finalmask)
    }

    @Test
    fun hysteria2_preservesQuicAndSalamanderFinalMaskWithoutCdnMutations() {
        val rawFm = """{"quicParams":{"congestion":"bbr","brutalUp":"30 mbps","brutalDown":"100 mbps"},"udp":[{"type":"salamander","settings":{"password":"sala_pw"}}]}"""
        val encodedFm = URLEncoder.encode(rawFm, "UTF-8")
        val uri = "hysteria2://hy2pass@203.0.113.10:1377?sni=realnd.example.com&alpn=h3%2Ch2&fp=chrome" +
            "&ech=EchSampleList&obfs=salamander&obfs-password=sala_pw&fm=$encodedFm#Hy2-Full"

        val parsed = Hysteria2Fmt.parse(uri)
        val outbound = CoreOutboundBuilder.convert(parsed)!!
        val stream = outbound.streamSettings!!

        assertEquals("hysteria", stream.network)
        assertEquals(AppConfig.TLS, stream.security)
        assertEquals("chrome", stream.tlsSettings?.fingerprint)
        assertNull("Hysteria2 must NOT receive CDN cipherSuites", stream.tlsSettings?.cipherSuites)
        assertEquals("EchSampleList", stream.tlsSettings?.echConfigList)
        assertNotNull(stream.finalmask)
        val fmJson = JsonUtil.toJson(stream.finalmask)
        assertTrue(fmJson.contains("quicParams"))
        assertTrue(fmJson.contains("salamander"))
        assertFalse(fmJson.contains("tlshello"))
    }

    @Test
    fun hysteria2_withoutExplicitFmStillRetainsBuiltSalamanderFinalMask() {
        val uri = "hysteria2://hy2pass@203.0.113.10:1377?sni=realnd.example.com&obfs=salamander&obfs-password=sala_only#Hy2-Plain"
        val parsed = Hysteria2Fmt.parse(uri)
        assertTrue(parsed.finalMask.isNullOrEmpty())

        val outbound = CoreOutboundBuilder.convert(parsed)!!
        val stream = outbound.streamSettings!!

        assertNotNull(stream.finalmask)
        val fmJson = JsonUtil.toJson(stream.finalmask)
        assertTrue("Built salamander mask must not be overwritten", fmJson.contains("salamander"))
        assertFalse(fmJson.contains("tlshello"))
        assertNull(stream.tlsSettings?.cipherSuites)
    }

    @Test
    fun standardTrojanAndVmess_doNotReceiveCloudflareTlsOrFragmentOverrides() {
        val trojan = TrojanFmt.parse("trojan://secret123@203.0.113.20:443?security=tls&sni=std.example.com&fp=firefox#Trojan-Std")
        val trojanOut = CoreOutboundBuilder.convert(trojan)!!
        assertEquals("firefox", trojanOut.streamSettings?.tlsSettings?.fingerprint)
        assertNull(trojanOut.streamSettings?.tlsSettings?.cipherSuites)
        assertNull(trojanOut.streamSettings?.finalmask)

        val vmess = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "VMess-Std"
            server = "203.0.113.21"
            serverPort = "443"
            password = "33333333-3333-3333-3333-333333333333"
            network = "ws"
            security = AppConfig.TLS
            sni = "vmess.example.com"
        }
        val vmessOut = CoreOutboundBuilder.convert(vmess)!!
        assertNull(vmessOut.streamSettings?.tlsSettings?.fingerprint)
        assertNull(vmessOut.streamSettings?.tlsSettings?.cipherSuites)
        assertNull(vmessOut.streamSettings?.finalmask)
    }

    @Test
    fun cloudflareCdn_preservesUnsafeFingerprintCipherSuitesAndFragmentMaskUnchanged() {
        val cfUri = "vless://44444444-4444-4444-4444-444444444444@104.16.10.20:2053" +
            "?type=xhttp&security=tls&sni=cf.mo3ibest.my.id&host=cf.mo3ibest.my.id&path=%2Fws#CF-Node"
        val parsed = VlessFmt.parse(cfUri)!!
        val outbound = CoreOutboundBuilder.convert(parsed)!!
        val stream = outbound.streamSettings!!

        assertEquals(AppConfig.TLS, stream.security)
        assertEquals("unsafe", stream.tlsSettings?.fingerprint)
        assertEquals(CloudflareScanner.PRODUCTION_CIPHERSUITES, stream.tlsSettings?.cipherSuites)
        assertNotNull(stream.finalmask)
        assertTrue(JsonUtil.toJson(stream.finalmask).contains("tlshello"))
    }

    @Test
    fun cloudFrontCdn_preservesChromeFingerprintWithoutCdnFragmentMask() {
        val frontUri = "vless://55555555-5555-5555-5555-555555555555@d111111abcdef8.cloudfront.net:443" +
            "?type=xhttp&security=tls&sni=d111111abcdef8.cloudfront.net&host=d111111abcdef8.cloudfront.net#CF-Front"
        val parsed = VlessFmt.parse(frontUri)!!
        val outbound = CoreOutboundBuilder.convert(parsed)!!
        val stream = outbound.streamSettings!!

        assertEquals(AppConfig.TLS, stream.security)
        assertEquals("chrome", stream.tlsSettings?.fingerprint)
        assertNull(stream.tlsSettings?.cipherSuites)
        assertNull(stream.finalmask)
    }
}
