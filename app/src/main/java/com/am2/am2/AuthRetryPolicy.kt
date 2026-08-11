package com.am2.am2

internal object AuthRetryPolicy {
    fun keepAuthorizedSession(hadAuthenticatedSession: Boolean): Boolean {
        return !hadAuthenticatedSession
    }
}
