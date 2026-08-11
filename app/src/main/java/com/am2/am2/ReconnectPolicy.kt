package com.am2.am2

internal object ReconnectPolicy {
    fun shouldReconnect(isAuthorized: Boolean, closeCode: Int): Boolean {
        return isAuthorized
    }
}
