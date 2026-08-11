package com.am2.am2.logging

import android.util.Log
import com.am2.am2.BuildConfig

/** Debug-only sanitized application logging. Direct android.util.Log use is rejected by CI. */
object SafeLog {
    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, LogSanitizer.sanitize(message))
    }

    fun i(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(tag, LogSanitizer.sanitize(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safe = if (error == null) message else "$message: ${error.javaClass.simpleName}"
        Log.w(tag, LogSanitizer.sanitize(safe))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safe = if (error == null) message else "$message: ${error.javaClass.simpleName}"
        Log.e(tag, LogSanitizer.sanitize(safe))
    }
}
