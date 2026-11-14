package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoOptimizationPolicyTest {

    @Test
    fun clampsAutoOptimizeIntervalToAllowedTwoToThreeHoursRange() {
        assertEquals(2, AutoOptimizationPolicy.clampIntervalHours(0))
        assertEquals(2, AutoOptimizationPolicy.clampIntervalHours(1))
        assertEquals(2, AutoOptimizationPolicy.clampIntervalHours(2))
        assertEquals(3, AutoOptimizationPolicy.clampIntervalHours(3))
        assertEquals(3, AutoOptimizationPolicy.clampIntervalHours(6))
    }

    @Test
    fun switchesOnUnhealthyOrMeaningfulGainAndKeepsWithinHysteresis() {
        val currentHealthy = CurrentEdgeHealth(ip = "104.16.0.1", healthy = true, uploadKBps = 1000L)
        val tinyNoise = MeasuredEdgeCandidate("104.16.0.2", passedGate = true, uploadKBps = 1030L, latencyMs = 50L)
        assertEquals(
            OptimizationDecision.KeepCurrent(KeepReason.WITHIN_HYSTERESIS),
            AutoOptimizationPolicy.evaluate(currentHealthy, tinyNoise),
        )

        val clearUpgrade = MeasuredEdgeCandidate("104.16.0.3", passedGate = true, uploadKBps = 1300L, latencyMs = 45L)
        assertEquals(
            OptimizationDecision.Switch(SwitchReason.MEANINGFUL_SPEED_GAIN),
            AutoOptimizationPolicy.evaluate(currentHealthy, clearUpgrade),
        )

        val unhealthy = CurrentEdgeHealth(ip = "104.16.0.1", healthy = false, uploadKBps = 0L)
        assertEquals(
            OptimizationDecision.Switch(SwitchReason.CURRENT_UNHEALTHY),
            AutoOptimizationPolicy.evaluate(unhealthy, tinyNoise),
        )
    }

    @Test
    fun edgeRollbackRestoresPriorEdgeWithoutCorruptingTransportFields() {
        val profile = ProfileItem().apply {
            server = "cdn.example.com"
            serverPort = "443"
            sni = "cdn.example.com"
            host = "cdn.example.com"
            path = "/ws-path"
            security = "tls"
            password = "uuid-preserved"
            qsEdgeIp = "5.10.214.1"
        }
        val snapshot = EdgeRollbackSnapshot(
            previousSelection = null,
            previousEdgesByGuid = mapOf("g1" to "5.10.214.1"),
        )
        EdgeApplication.apply(
            listOf(EdgeApplication.Target("g1", profile)),
            "13.32.10.5",
        )
        assertEquals("13.32.10.5", profile.qsEdgeIp)
        assertEquals("cdn.example.com", profile.server)
        assertEquals("cdn.example.com", profile.sni)
        assertEquals("/ws-path", profile.path)
        assertEquals("uuid-preserved", profile.password)

        snapshot.restoreTo(mapOf("g1" to profile))
        assertEquals("5.10.214.1", profile.qsEdgeIp)
        assertTrue(profile.security == "tls")
    }
}
