package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean("start_on_boot", false)
            
            if (startOnBoot) {
                SafeLog.d("BootReceiver", "Starting TIK App on Boot...")
                val launchIntent = Intent(context, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
