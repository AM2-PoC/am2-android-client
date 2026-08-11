package com.am2.am2.logging

import android.util.Log

/** Sanitized application logging. Direct android.util.Log use is rejected by CI. */
object SafeLog {
    fun d(tag: String, message: String) {
        Log.d(tag, LogSanitizer.sanitize(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, LogSanitizer.sanitize(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(tag, LogSanitizer.sanitize(message))
        } else {
            Log.w(tag, LogSanitizer.sanitize("$message: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"))
        }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val safe = if (error == null) {
            message
        } else {
            "$message: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
        Log.e(tag, LogSanitizer.sanitize(safe))
    }
}
