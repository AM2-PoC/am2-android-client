package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDiagnosticsTest {
    @Test
    fun reportContainsOperationalFieldsWithoutCredentials() {
        val report = DeviceDiagnostics.format(
            appVersion = "1.0.1",
            sdkInt = 35,
            manufacturer = "Acme",
            model = "PTT-1",
            network = "WIFI",
            socketConnected = true,
            socketAuthenticated = true,
            communicationState = "IDLE",
            reconnectAttempts = 2,
            lastDisconnect = "closed code=1000",
        )

        assertTrue(report.contains("App: 1.0.1"))
        assertTrue(report.contains("Android: API 35"))
        assertTrue(report.contains("Device: Acme PTT-1"))
        assertTrue(report.contains("Network: WIFI"))
        assertTrue(report.contains("Socket: connected, authenticated"))
        assertTrue(report.contains("Reconnect attempts: 2"))
        assertTrue(report.contains("Last disconnect: closed code=1000"))
        assertFalse(report.contains("password", ignoreCase = true))
        assertFalse(report.contains("token", ignoreCase = true))
    }
}
