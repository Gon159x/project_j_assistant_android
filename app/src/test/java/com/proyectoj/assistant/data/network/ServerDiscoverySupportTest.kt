package com.proyectoj.assistant.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoverySupportTest {
    @Test
    fun normalizePublicBaseUrl_addsHttpsWhenSchemeMissing() {
        assertEquals(
            "https://assistant.example.com",
            ServerDiscoverySupport.normalizePublicBaseUrl("assistant.example.com/")
        )
    }

    @Test
    fun normalizePublicBaseUrl_returnsNullForBlankInput() {
        assertNull(ServerDiscoverySupport.normalizePublicBaseUrl("   "))
    }

    @Test
    fun buildDirectCandidates_prioritizesEmulatorAndFallbackThenSubnet() {
        val candidates = ServerDiscoverySupport.buildDirectCandidates(
            prefix = "192.168.1",
            defaultPort = 8000,
            emulatorBaseUrl = "http://10.0.2.2:8000",
            fallbackBaseUrl = "http://192.168.1.2:8000"
        ).toList()

        assertEquals("http://10.0.2.2:8000", candidates.first())
        assertEquals("http://192.168.1.2:8000", candidates[1])
        assertTrue(candidates.contains("http://192.168.1.200:8000"))
    }
}
