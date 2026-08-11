package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build

object SoundManager {
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var currentStreamType = AudioManager.STREAM_MUSIC
    private var isMuted = false

    // Pengaturan Volume Statis (0.0f hingga 1.0f)
    private const val VOL_PUSH = 0.2f
    private const val VOL_END = 0.8f
    private const val VOL_RX_START = 0.5f
    private const val VOL_RX_STOP = 0.8f
    private const val VOL_DTMF = 0.2f

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext?.getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)

        val audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.let {
            currentStreamType = AudioDeviceManager.getCurrentStreamType(it)
        }
    }

    fun updateRouting(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val newStreamType = AudioDeviceManager.getCurrentStreamType(audioManager)

        if (newStreamType != currentStreamType) {
            currentStreamType = newStreamType
        }
    }

    private fun play(resId: Int, volume: Float) {
        val context = appContext ?: return
        if (isMuted) return

        try {
            val mediaPlayer = MediaPlayer.create(context, resId) ?: return
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                mediaPlayer.setAudioAttributes(attributes)
            } else {
                @Suppress("DEPRECATION")
                mediaPlayer.setAudioStreamType(currentStreamType)
            }

            mediaPlayer.setVolume(volume, volume)
            mediaPlayer.setOnCompletionListener { mp ->
                mp.release()
            }
            mediaPlayer.start()
        } catch (e: Exception) {
            SafeLog.e("Exception", "Operation failed", e)
        }
    }

    fun playStartTx() {
        val gatewayMode = prefs?.getBoolean("gateway_mode", false) ?: false
        val dtmfMode = prefs?.getBoolean("dtmf_mode", false) ?: false
        val soundPush = prefs?.getBoolean("sound_push", true) ?: true
        
        if (!isMuted && !gatewayMode && !dtmfMode && soundPush) {
            play(R.raw.push, VOL_PUSH)
        }
    }

    fun playStopTx() {
        val gatewayMode = prefs?.getBoolean("gateway_mode", false) ?: false
        val dtmfMode = prefs?.getBoolean("dtmf_mode", false) ?: false
        val soundPush = prefs?.getBoolean("sound_push", true) ?: true

        if (!isMuted && !gatewayMode && !dtmfMode && soundPush) {
            play(R.raw.end, VOL_END)
        }
    }

    fun playRxStart() {
        val gatewayMode = prefs?.getBoolean("gateway_mode", false) ?: false
        val dtmfMode = prefs?.getBoolean("dtmf_mode", false) ?: false
        val soundRx = prefs?.getBoolean("sound_rx", true) ?: true

        if (!isMuted) {
            if (dtmfMode) {
                play(R.raw.on, VOL_DTMF)
            } else if (!gatewayMode && soundRx) {
                play(R.raw.rx, VOL_RX_START)
            }
        }
    }

    fun playRxStop() {
        val gatewayMode = prefs?.getBoolean("gateway_mode", false) ?: false
        val dtmfMode = prefs?.getBoolean("dtmf_mode", false) ?: false
        val soundRx = prefs?.getBoolean("sound_rx", true) ?: true

        if (!isMuted) {
            if (dtmfMode) {
                play(R.raw.off, VOL_DTMF)
            } else if (!gatewayMode && soundRx) {
                play(R.raw.end, VOL_RX_STOP)
            }
        }
    }

    fun setMute(mute: Boolean) {
        isMuted = mute
    }

    fun release() {
        // MediaPlayer di-release otomatis setelah selesai diputar.
    }
}
