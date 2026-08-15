package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

class AudioDeviceManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isCommActive = false

    private var keepAliveTrack: AudioTrack? = null
    @Volatile private var isKeepAliveRunning = false
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothHeadset: BluetoothHeadset? = null
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                SafeLog.d("AudioDeviceManager", "AudioFocus Loss")
                AudioPlayer.stop()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                SafeLog.d("AudioDeviceManager", "AudioFocus Gained")
            }
        }
    }

    companion object {
        @Volatile
        var isBluetoothConnected = false
            private set

        @Volatile
        var isScoConnected = false
            private set

        /*
         * Whether capture may open now.
         *
         * Only Bluetooth has an asynchronous route handshake: startBluetoothSco()
         * requests the link and the system reports CONNECTED later. Wired, USB and
         * the built-in microphone are usable as soon as they are selected.
         */
        fun isCaptureRouteReady(): Boolean = !isBluetoothConnected || isScoConnected

        fun getCurrentStreamType(audioManager: AudioManager): Int {
            val hasAccessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any { device ->
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> true
                        else -> false
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothA2dpOn || audioManager.isWiredHeadsetOn
            }
            return if (hasAccessory) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        }
    }

    interface OnDeviceChangeListener {
        fun onDeviceChanged(deviceType: String)
    }

    private var listener: OnDeviceChangeListener? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                updateDeviceStatus()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                updateDeviceStatus()
            }
        }
    }

    private val hardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_HEADSET_PLUG -> updateDeviceStatus()
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> updateDeviceStatus()
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    // startBluetoothSco() only requests the link; this broadcast
                    // reports when it is actually carrying audio. Capture must
                    // wait for CONNECTED, because AudioRecord binds its input
                    // route on construction and will not move onto a link that
                    // connects afterwards.
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    isScoConnected = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
                    updateDeviceStatus()
                    if (isScoConnected) WebSocketManager.onCaptureRouteReady()
                }
            }
        }
    }

    fun start(listener: OnDeviceChangeListener) {
        this.listener = listener
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(hardwareReceiver, filter)
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            SafeLog.e("AudioDeviceManager", "Start Error: ${e.message}")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Api23Impl.registerCallback(audioManager, this)
        }
        
        updateDeviceStatus()
    }

    fun setCommunicationMode(active: Boolean) {
        if (this.isCommActive != active) {
            this.isCommActive = active
            
            // Batalkan penundaan penutupan mode jika ada aktivitas baru
            mainHandler.removeCallbacksAndMessages("STOP_COMM_MODE")

            if (active) {
                requestAudioFocus()
                updateDeviceStatus()
            } else {
                // HANG TIME: Memberikan jeda 1.5 detik sebelum menutup SCO/jalur audio.
                // Ini sangat penting agar nada STOP (Roger Beep) sempat terdengar di headset
                // sebelum koneksinya diputus oleh sistem.
                mainHandler.postAtTime({
                    if (!isCommActive) {
                        abandonAudioFocus()
                        updateDeviceStatus()
                    }
                }, "STOP_COMM_MODE", SystemClock.uptimeMillis() + 1500L)
            }
        }
    }

    private fun toggleKeepAlive(enable: Boolean) {
        if (enable && !isKeepAliveRunning && isBluetoothConnected) {
            startKeepAlive()
        } else if (!enable) {
            isKeepAliveRunning = false
        }
    }

    private fun startKeepAlive() {
        if (isKeepAliveRunning) return
        isKeepAliveRunning = true
        executor.execute {
            try {
                val sampleRate = 16000
                val bufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                
                keepAliveTrack = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize.coerceAtLeast(2048),
                    AudioTrack.MODE_STREAM
                )
                
                keepAliveTrack?.play()
                val silentBuffer = ShortArray(640)
                
                while (isKeepAliveRunning && isBluetoothConnected) {
                    keepAliveTrack?.write(silentBuffer, 0, silentBuffer.size)
                    SystemClock.sleep(500)
                }
            } catch (e: Exception) {
                SafeLog.e("AudioDeviceManager", "KeepAlive Error: ${e.message}")
            } finally {
                isKeepAliveRunning = false
                try {
                    keepAliveTrack?.stop()
                    keepAliveTrack?.release()
                } catch (e: Exception) {}
                keepAliveTrack = null
            }
        }
    }

    fun stop() {
        isKeepAliveRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(hardwareReceiver)
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Api23Impl.unregisterCallback(audioManager)
            }
            abandonAudioFocus()
            resetAudioToNormal()
            listener = null
        } catch (e: Exception) {
            SafeLog.e("AudioDeviceManager", "Error stopping: ${e.message}")
        }
    }

    fun requestAudioFocus() {
        try {
            val deviceStatus = getHardwareDeviceStatus()
            val hasAccessory = deviceStatus.hasBluetooth || deviceStatus.hasWired || deviceStatus.hasUsb

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val usage = if (hasAccessory) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
                val contentType = if (hasAccessory) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
                
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                val stream = if (hasAccessory) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(audioFocusChangeListener, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            if (hasAccessory) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
                if (deviceStatus.hasBluetooth) {
                    @Suppress("DEPRECATION")
                    if (!audioManager.isBluetoothScoOn) {
                        try { audioManager.startBluetoothSco() } catch (e: Exception) {}
                    }
                }
            } else {
                try { audioManager.stopBluetoothSco() } catch (e: Exception) {}
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            SafeLog.e("AudioDeviceManager", "Focus Request Error: ${e.message}")
        }
    }

    fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            
            try { audioManager.stopBluetoothSco() } catch (e: Exception) {}
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            SafeLog.e("AudioDeviceManager", "Focus Abandon Error: ${e.message}")
        }
    }

    fun resetAudioToNormal() {
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
    }

    private fun getHardwareDeviceStatus(): DeviceStatus {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Api23Impl.getDeviceStatus(audioManager)
        } else {
            @Suppress("DEPRECATION")
            DeviceStatus(audioManager.isBluetoothA2dpOn, audioManager.isWiredHeadsetOn, false)
        }
    }

    fun updateDeviceStatus() {
        val deviceStatus = getHardwareDeviceStatus()
        isBluetoothConnected = deviceStatus.hasBluetooth
        // A link that is gone cannot still be carrying audio; leaving this set
        // would report a ready route for a headset that has been switched off.
        if (!deviceStatus.hasBluetooth) isScoConnected = false

        val currentDevice = when {
            deviceStatus.hasBluetooth -> "Bluetooth Headset"
            deviceStatus.hasUsb -> "USB Device / Soundcard"
            deviceStatus.hasWired -> "Wired Headset"
            else -> "Built-in Speaker"
        }

        // KeepAlive hanya untuk Bluetooth
        toggleKeepAlive(deviceStatus.hasBluetooth)

        val useVoiceComm = deviceStatus.hasBluetooth || deviceStatus.hasWired || deviceStatus.hasUsb
        
        AudioPlayer.updateAudioRouting(useVoiceComm)
        AudioRecorder.updateAudioSource(useVoiceComm)

        listener?.onDeviceChanged(currentDevice)
    }

    data class DeviceStatus(val hasBluetooth: Boolean, val hasWired: Boolean, val hasUsb: Boolean)

    @RequiresApi(Build.VERSION_CODES.M)
    private object Api23Impl {
        private var audioDeviceCallback: AudioDeviceCallback? = null

        @JvmStatic
        fun registerCallback(audioManager: AudioManager, manager: AudioDeviceManager) {
            if (audioDeviceCallback == null) {
                audioDeviceCallback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        manager.updateDeviceStatus()
                    }
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        manager.updateDeviceStatus()
                    }
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        @JvmStatic
        fun unregisterCallback(audioManager: AudioManager) {
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        }

        @JvmStatic
        fun getDeviceStatus(audioManager: AudioManager): DeviceStatus {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var hasBt = false
            var hasWired = false
            var hasUsb = false
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> hasBt = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> hasWired = true
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> hasUsb = true
                }
            }
            return DeviceStatus(hasBt, hasWired, hasUsb)
        }
    }
}
