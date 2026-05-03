package com.am2.am2

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

open class BaseActivity : AppCompatActivity() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val talkingObserver = Observer<String> { status ->
        updateScreenFlags(status)
        applyStatusBarColorByStatus(status)
    }

    private val ptpTargetObserver = Observer<String?> { targetId ->
        if (targetId != null) {
            val isPtpVideo = WebSocketManager.isPtpVideo.value == true
            if (isPtpVideo) {
                if (this !is VideoActivity) {
                    val intent = Intent(this, VideoActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                }
            } else {
                if (this !is MainActivity && this !is VideoActivity) {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Harus diset SEBELUM super.onCreate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
        
        super.onCreate(savedInstanceState)
        applyScreenSettings()
        
        WebSocketManager.talkingStatus.observe(this, talkingObserver)
        WebSocketManager.ptpTargetId.observe(this, ptpTargetObserver)

        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onResume() {
        super.onResume()
        applyScreenSettings()
        // Refresh warna saat kembali aktif
        applyStatusBarColorByStatus(WebSocketManager.talkingStatus.value ?: "IDLE")
    }

    private fun applyStatusBarColorByStatus(status: String) {
        val s = status.lowercase()
        val colorRes = when {
            // TX: You are Speaking
            s.contains("you are speaking") || s.contains("private to") -> android.R.color.holo_red_dark
            // RX: Someone is Speaking
            s.contains("is speaking") || s.contains("are speaking") || s.contains("private from") -> android.R.color.holo_green_dark
            // IDLE / Default
            else -> android.R.color.black
        }
        updateStatusBarColor(colorRes)
    }

    private fun updateStatusBarColor(colorRes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runOnUiThread {
                try {
                    window.statusBarColor = ContextCompat.getColor(this, colorRes)
                    
                    // Pastikan teks ikon status bar tetap putih (Bukan Light Mode)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val decorView = window.decorView
                        var flags = decorView.systemUiVisibility
                        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                        decorView.systemUiVisibility = flags
                    }
                } catch (e: Exception) {
                    Log.e("BaseActivity", "Gagal ubah warna: ${e.message}")
                }
            }
        }
    }

    open fun applyScreenSettings() {
        val prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val kgm = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            kgm.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun updateScreenFlags(status: String) {
        val prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_wake", false)) {
            if (status.contains("Speaking", ignoreCase = true)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                wakeScreen()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == 23 || keyCode == 66) {
                wakeScreen()
            }
        }

        val prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        val pttKey = prefs.getInt("ptt_key", -1)
        val pttSource = prefs.getString("ptt_source", "") ?: ""

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            if (pttSource == "Volume Key" && keyCode == pttKey) {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val isToggle = prefs.getBoolean("ptt_toggle", false)
                    if (isToggle) {
                        if (WebSocketManager.isTalking.value == true) WebSocketManager.stopTalking()
                        else WebSocketManager.startTalking()
                    } else {
                        WebSocketManager.startTalking()
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    val isToggle = prefs.getBoolean("ptt_toggle", false)
                    if (!isToggle) WebSocketManager.stopTalking()
                }
                return true
            } else {
                if (action == KeyEvent.ACTION_DOWN) {
                    handleManualVolumeAdjustment(isUp)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleManualVolumeAdjustment(isUp: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeStream = if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val direction = if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(activeStream, direction, AudioManager.FLAG_SHOW_UI)
    }

    protected fun wakeScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        if (!isScreenOn) {
            if (wakeLock == null) {
                @Suppress("DEPRECATION")
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TIK:WakeLock"
                )
            }
            try { 
                if (wakeLock?.isHeld == false) wakeLock?.acquire(10000L) 
            } catch (e: Exception) {
                Log.e("BaseActivity", "Gagal acquire wakeLock: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (e: Exception) {}
        }
    }
}
