package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Bring the radio back when the handset comes back.
 *
 * This used to call startActivity(LoginActivity). Android 10 forbids starting
 * an activity from the background, and a broadcast receiver is the background,
 * so from API 29 onwards the system dropped the launch without an error, a
 * toast, or anything the operator could see. The setting had been doing
 * nothing at all on every handset in the fleet -- while being labelled "Auto
 * Login", which is how it came to be blamed for sessions that would not resume.
 *
 * An activity was the wrong thing to want anyway. A radio coming out of a
 * reboot needs the service that holds the socket and the microphone, not a
 * login screen in the operator's face. PTTService initialises WebSocketManager,
 * AudioRecorder and SoundManager in its own onCreate, so it runs with no
 * activity above it, and it stops itself when there is no session to resume.
 *
 * Starting a foreground service is also what BOOT_COMPLETED is permitted to do.
 */
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
            // An OEM that refuses this is a real answer, and a silent one is
            // what hid the previous fault for so long.
            SafeLog.e("BootReceiver", "the radio service could not be started after boot", e)
        }
    }
}
