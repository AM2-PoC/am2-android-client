package com.am2.am2

internal object DeviceDiagnostics {
    fun format(
        appVersion: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        network: String,
        socketConnected: Boolean,
        socketAuthenticated: Boolean,
        communicationState: String,
        reconnectAttempts: Int,
        lastDisconnect: String,
    ): String = buildString {
        appendLine("App: $appVersion")
        appendLine("Android: API $sdkInt")
        appendLine("Device: $manufacturer $model")
        appendLine("Network: $network")
        append("Socket: ")
        append(if (socketConnected) "connected" else "disconnected")
        appendLine(if (socketAuthenticated) ", authenticated" else ", unauthenticated")
        appendLine("Communication: $communicationState")
        appendLine("Reconnect attempts: $reconnectAttempts")
        append("Last disconnect: $lastDisconnect")
    }
}
