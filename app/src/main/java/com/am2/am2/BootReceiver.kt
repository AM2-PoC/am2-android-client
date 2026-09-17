package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", false)) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context.applicationContext, PTTService::class.java),
            )
            SafeLog.i("BootReceiver", "asked the radio service to come up after boot")
        } catch (e: Exception) {

            SafeLog.e("BootReceiver", "the radio service could not be started after boot", e)
        }
    }
}
