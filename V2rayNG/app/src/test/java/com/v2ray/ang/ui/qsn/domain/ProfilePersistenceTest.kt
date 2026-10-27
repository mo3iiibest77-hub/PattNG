package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored profile survives a save/reload round trip.
 *
 * The edge is an added field: an older profile without it must still decode,
 * and a profile with it must keep both the edge and the origin.
 */
class ProfilePersistenceTest {

    private fun profile() = ProfileItem.create(EConfigType.VLESS).apply {
        subscriptionId = "sub-1"
        remarks = "my config"
        server = "origin.example.com"
        serverPort = "443"
        network = "ws"
        security = "tls"
        password = "uuid-1234"
        sni = "sni.example.com"
        host = "host.example.com"
        path = "/ws"
        cipherSuites = "CUSTOM_CIPHERS"
        finalMask = "{\"custom\":true}"
        qsEdgeIp = "104.16.1.1"
    }

    @Test
    fun `the edge and the origin both survive a round trip`() {
        val json = JsonUtil.toJson(profile())
        val restored = JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
        assertEquals("104.16.1.1", restored?.qsEdgeIp)
        assertEquals("origin.example.com", restored?.server)
        assertEquals("CUSTOM_CIPHERS", restored?.cipherSuites)
        assertEquals("{\"custom\":true}", restored?.finalMask)
        assertEquals("sni.example.com", restored?.sni)
        assertEquals("/ws", restored?.path)
    }

    @Test
    fun `a profile stored before the edge existed still decodes`() {
        val legacy = "{\"configType\":\"VLESS\",\"server\":\"old.example.com\",\"serverPort\":\"443\"}"
        val restored = JsonUtil.fromJsonSafe(legacy, ProfileItem::class.java)
        assertEquals("old.example.com", restored?.server)
        assertNull(restored?.qsEdgeIp)
    }

    @Test
    fun `the edge is not part of the duplicate identity`() {
        val withEdge = profile()
        val without = profile().apply { qsEdgeIp = null }
        assertEquals(without.duplicateIdentity(), withEdge.duplicateIdentity())
        assertNull(withEdge.duplicateIdentity().qsEdgeIp)
    }

    @Test
    fun `a re-imported config is still recognised as a duplicate`() {
        val first = profile()
        val second = profile().apply { qsEdgeIp = "104.16.9.9" }
        assertEquals(first.duplicateIdentity(), second.duplicateIdentity())
        assertTrue(first.duplicateIdentity().server == second.duplicateIdentity().server)
    }
}
