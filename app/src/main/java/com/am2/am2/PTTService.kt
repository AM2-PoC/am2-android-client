package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import java.util.*

class PTTService : Service() {

    private val TAG = "PTTService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var audioDeviceManager: AudioDeviceManager
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var tempGatt: BluetoothGatt? = null

    private val CHANNEL_ID = "PTT_SERVICE_CHANNEL"
    private val NOTIFICATION_ID = 123

    private var volumeProvider: VolumeProviderCompat? = null
    private var backgroundLocationListener: Any? = null
    private var wasSomeoneElseTalking = false

    companion object {
        const val ACTION_START_PTT = "com.am2.am2.ACTION_START_PTT"
        const val ACTION_STOP_PTT = "com.am2.am2.ACTION_STOP_PTT"
        const val ACTION_UPDATE_VOX = "com.am2.am2.ACTION_UPDATE_VOX"

        /*
         * The recorder asking for VOX back after losing the microphone.
         *
         * Separate from ACTION_UPDATE_VOX because it carries a delay: the
         * recording thread cannot serve its own wait -- the next
         * startRecording joins it, so a thread that slept before exiting would
         * make that join time out and refuse the restart it just asked for.
         */
        const val ACTION_VOX_RESTART = "com.am2.am2.ACTION_VOX_RESTART"
        const val EXTRA_RESTART_DELAY_MS = "restart_delay_ms"
    }

    private val talkingStatusObserver = Observer<String> { status ->
        checkAndWakeScreen(status)
    }

    private val connectionStatusObserver = Observer<Boolean> { isConnected ->
        updateServiceNotification(isConnected)
        if (isConnected) {
            tryConnectBlePtt()
            checkVoxState()
        }
    }

    private val loginEventObserver = Observer<WebSocketManager.LoginEvent?> { event ->
        if (event is WebSocketManager.LoginEvent.ForceLogout) {
            handleForceLogout()
        }
    }

    private val communicationActiveObserver = Observer<Boolean> { isActive ->
        if (::audioDeviceManager.isInitialized) {
            audioDeviceManager.setCommunicationMode(isActive)
            updateVolumeProvider()
        }
    }

    private val foregroundObserver = Observer<Boolean> { 
        updateVolumeProvider()
    }

    private val activeSpeakersObserver = Observer<Set<String>> { speakers ->
        val isNowSomeoneTalking = speakers.isNotEmpty()
        val isMeTalking = WebSocketManager.isTalkingNow()
        
        // Hanya bunyikan nada RX jika rekan mulai bicara DAN kita tidak sedang TX
        if (isNowSomeoneTalking && !wasSomeoneElseTalking && !isMeTalking) {
            SoundManager.playRxStart()
        } else if (!isNowSomeoneTalking && wasSomeoneElseTalking) {
            SoundManager.playRxStop()
        }
        wasSomeoneElseTalking = isNowSomeoneTalking
    }

    override fun onCreate() {
        super.onCreate()
        WebSocketManager.init(applicationContext)
        AudioRecorder.init(this)
        prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audioDeviceManager = AudioDeviceManager(this)
        audioDeviceManager.start(object : AudioDeviceManager.OnDeviceChangeListener {
            override fun onDeviceChanged(deviceType: String) {
                SafeLog.d(TAG, "Device changed to: $deviceType")
                SoundManager.updateRouting(applicationContext)
            }
        })

        initBluetoothAdapter()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TIK:PTT_WAKE_LOCK")
        if (wakeLock?.isHeld == false) {
            try { wakeLock?.acquire() } catch (e: Exception) {}
        }

        setupMediaSession()
        SoundManager.init(this)

        WebSocketManager.talkingStatus.observeForever(talkingStatusObserver)
        WebSocketManager.connectionStatus.observeForever(connectionStatusObserver)
        WebSocketManager.loginEvent.observeForever(loginEventObserver)
        WebSocketManager.isCommunicationActive.observeForever(communicationActiveObserver)
        WebSocketManager.activeSpeakersList.observeForever(activeSpeakersObserver)
        AppStatus.isForeground.observeForever(foregroundObserver)

        WebSocketManager.connect()
        tryConnectBlePtt()
        checkVoxState()
        startBackgroundLocationReporting()
    }

    private fun checkVoxState() {
        val voxEnabled = prefs.getBoolean("vox_enabled", false)
        val gatewayMode = prefs.getBoolean("gateway_mode", false)
        
        AudioRecorder.voxEnabled = voxEnabled
        AudioRecorder.gatewayModeEnabled = gatewayMode
        AudioRecorder.setVoxThreshold(
            prefs.getInt("vox_threshold", AudioRecorder.VOX_THRESHOLD_DEFAULT),
        )

        if ((voxEnabled || gatewayMode) && WebSocketManager.isConnected()) {
            AudioRecorder.startRecording()
        } else if (!voxEnabled && !gatewayMode) {
            // Hentikan rekaman secara paksa jika VOX dan Gateway dimatikan
            AudioRecorder.stopRecording(true)
            
            // Jika sedang TX (berbicara) saat VOX/Gateway dimatikan, paksa berhenti bicara
            if (WebSocketManager.isTalkingNow()) {
                performStopTalking()
            }
        }
    }

    private fun startBackgroundLocationReporting() {
        backgroundLocationListener = LocationHelper.startLiveLocationUpdates(this) { lat, lon, acc, _ ->
            if (lat != 0.0) {
                WebSocketManager.updateLocation(lat, lon, acc)
            }
        }
    }

    private fun handleForceLogout() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("FORCE_LOGOUT", true)
        }
        startActivity(intent)
        handler.post { Toast.makeText(applicationContext, "Sesi Anda telah diakhiri oleh admin.", Toast.LENGTH_LONG).show() }
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private fun initBluetoothAdapter() {
        bluetoothAdapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter
        } else {
            @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        }
    }

    private fun tryConnectBlePtt() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            closeBleGatt()
            return
        }
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_DENIED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        }

        val pttMac = prefs.getString("ptt_mac", "00:00:00:00:00:00") ?: ""
        val pttSource = prefs.getString("ptt_source", "") ?: ""
        val pttKey = prefs.getInt("ptt_key", -1)

        val isBleSource = pttSource.contains("BLE", ignoreCase = true) || (pttKey == 0 && pttMac != "00:00:00:00:00:00")

        if (!isBleSource || pttMac == "00:00:00:00:00:00") {
            closeBleGatt()
            return
        }

        if (tempGatt == null) {
            try {
                val device = adapter.getRemoteDevice(pttMac)
                tempGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    device.connectGatt(this, true, gattCallback)
                } else null
            } catch (e: Exception) {}
        }
    }

    private fun closeBleGatt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                tempGatt?.let {
                    try { it.disconnect(); it.close() } catch (e: Exception) {}
                }
            }
        }
        tempGatt = null
    }

    private fun updateServiceNotification(isConnected: Boolean) {
        val isSocketActuallyConnected = WebSocketManager.isConnectedOnSocket()
        val statusText = when {
            isSocketActuallyConnected -> "Terhubung ke Server"
            isConnected -> "Otorisasi akun..."
            else -> "Mencoba menyambung kembali..."
        }
        val notification = createNotification("PTT Aktif", statusText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun checkAndWakeScreen(status: String) {
        val autoWake = prefs.getBoolean("auto_wake", false)
        val isConversationActive = status.contains("Speaking", ignoreCase = true)

        if (autoWake && isConversationActive) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TIK:ScreenWakeLock"
                )
            }
            if (screenWakeLock?.isHeld == false) {
                try { screenWakeLock?.acquire(30000L) } catch (e: Exception) {}
            }

            val pmInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive else @Suppress("DEPRECATION") pm.isScreenOn

            if (!pmInteractive) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("trigger_wake", true)
                }
                startActivity(intent)
            }
        } else {
            if (screenWakeLock?.isHeld == true) {
                try { screenWakeLock?.release() } catch (e: Exception) {}
            }
        }
    }

    private fun setupMediaSession() {
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "PTT_SESSION", mediaButtonReceiver, null).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION") mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (event != null) {
                        val keyCode = event.keyCode
                        val action = event.action

                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            val pttSource = prefs.getString("ptt_source", "") ?: ""
                            val savedKey = prefs.getInt("ptt_key", -1)

                            if (pttSource == "Volume Key" && keyCode == savedKey) {
                                if (action == KeyEvent.ACTION_DOWN) {
                                    handleVolumeKeyAction(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                                } else if (action == KeyEvent.ACTION_UP) {
                                    if (!prefs.getBoolean("ptt_toggle", false)) {
                                        performStopTalking()
                                    }
                                }
                                return true
                            }
                            return false
                        }

                        val savedKey = prefs.getInt("ptt_key", -1)
                        val pttSource = prefs.getString("ptt_source", "") ?: ""
                        
                        // Perbaikan: Macaddress hanya divalidasi jika sumbernya adalah PTT BLE
                        val isBleSource = pttSource.contains("BLE", ignoreCase = true)
                        val isCorrectDevice = if (isBleSource) {
                            val savedMac = prefs.getString("ptt_mac", "00:00:00:00:00:00")
                            val deviceMac = getDeviceAddressSafe(event.device)
                            savedMac == "00:00:00:00:00:00" || savedMac == deviceMac
                        } else {
                            true // Jika bukan BLE (misal Headset Kabel), abaikan cek MAC
                        }

                        if (isCorrectDevice && (keyCode == savedKey || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                            if (event.repeatCount == 0) {
                                handlePttAction(action)
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            }, handler)

            updateVolumeProvider()
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
    }

    private fun getActiveStream(): Int {
        return if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    private fun updateVolumeProvider() {
        val pttSource = prefs.getString("ptt_source", "") ?: ""
        val isCommunicationActive = WebSocketManager.isCommunicationActive.value ?: false

        val shouldBeRemote = pttSource == "Volume Key"

        if (!shouldBeRemote) {
            mediaSession?.setPlaybackToLocal(getActiveStream())
            volumeProvider = null
        } else {
            val activeStream = getActiveStream()
            val max = audioManager.getStreamMaxVolume(activeStream)
            val cur = audioManager.getStreamVolume(activeStream)

            volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, max, cur) {
                override fun onAdjustVolume(direction: Int) {
                    handler.post {
                        if (direction > 0) handleVolumeKeyAction(true)
                        else if (direction < 0) handleVolumeKeyAction(false)
                    }
                }
            }
            mediaSession?.setPlaybackToRemote(volumeProvider!!)
        }

        val state = if (isCommunicationActive) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_STOPPED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(state, 0, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun handleVolumeKeyAction(isUp: Boolean) {
        val savedKey = prefs.getInt("ptt_key", -1)
        val pttSource = prefs.getString("ptt_source", "") ?: ""
        val targetKeyCode = if (isUp) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN

        if (pttSource == "Volume Key" && savedKey == targetKeyCode) {
            val pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)
            val isCurrentlyTalking = WebSocketManager.isTalkingNow()

            if (pttToggleEnabled) {
                if (!isCurrentlyTalking) performStartTalking() else performStopTalking()
            } else {
                performStartTalking()
                handler.removeCallbacksAndMessages("VOL_PTT_END")
                handler.postAtTime(Runnable { performStopTalking() }, "VOL_PTT_END", SystemClock.uptimeMillis() + 800)
            }
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, 0)
        } else {
            val activeStream = getActiveStream()
            val direction = if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(activeStream, direction, AudioManager.FLAG_SHOW_UI)
            volumeProvider?.currentVolume = audioManager.getStreamVolume(activeStream)
        }
    }

    private fun handlePttAction(action: Int) {
        val pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)
        val isCurrentlyTalking = WebSocketManager.isTalkingNow()

        if (pttToggleEnabled) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (!isCurrentlyTalking) performStartTalking()
                else performStopTalking()
            }
        } else {
            if (action == KeyEvent.ACTION_DOWN) performStartTalking()
            else if (action == KeyEvent.ACTION_UP) performStopTalking()
        }
    }

    private fun performStartTalking(fromVox: Boolean = false) {
        val voxEnabled = prefs.getBoolean("vox_enabled", false)
        val isPtpActive = WebSocketManager.ptpTargetId.value != null

        if (voxEnabled && !fromVox && !isPtpActive) {
            /*
             * VOX owns the transmit decision, so the press is refused -- but
             * say so. The on-screen button is dimmed and explains itself; a
             * Bluetooth or wired PTT button arrives here with no affordance at
             * all and used to get nothing back, which is indistinguishable
             * from a button that has stopped working.
             */
            SoundManager.playRefused()
            return
        }

        val isRx = WebSocketManager.isRxOnly.value ?: false
        if (isRx && !isPtpActive) {
            if (AppStatus.isForeground.value == true) {
                handler.post { Toast.makeText(applicationContext, "Mode RX Only Aktif", Toast.LENGTH_SHORT).show() }
            }
            return
        }

        WebSocketManager.startTalking()
    }

    private fun performStopTalking() {
        /*
         * Disarm the volume-key timer.
         *
         * Volume-key PTT has no release event, so a timer ends the
         * transmission. It was never cancelled when a stop arrived by any other
         * route, so it stayed armed and fired later — ending the NEXT
         * transmission, at a moment nothing on screen explained.
         */
        handler.removeCallbacksAndMessages("VOL_PTT_END")
        WebSocketManager.stopTalking()
    }

    private val gattCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            }
            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"))
                    val char = service?.getCharacteristic(UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"))
                    if (char != null) {
                        gatt.setCharacteristicNotification(char, true)
                        val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                }
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val isDown = (data[0].toInt() == 1 || data[0].toInt() == 0x01)
                    handlePttAction(if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP)
                }
            }
        }
    } else null

    private fun getDeviceAddressSafe(device: android.view.InputDevice?): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device?.descriptor ?: "00:00:00:00:00:00"
            } else "00:00:00:00:00:00"
        } catch (e: Exception) { "00:00:00:00:00:00" }
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(content).setContentIntent(pendingIntent).setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PTT Service Channel", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WebSocketManager.talkingStatus.removeObserver(talkingStatusObserver)
        WebSocketManager.connectionStatus.removeObserver(connectionStatusObserver)
        WebSocketManager.loginEvent.removeObserver(loginEventObserver)
        WebSocketManager.isCommunicationActive.removeObserver(communicationActiveObserver)
        WebSocketManager.activeSpeakersList.removeObserver(activeSpeakersObserver)
        AppStatus.isForeground.removeObserver(foregroundObserver)
        closeBleGatt()
        try {
            audioDeviceManager.stop()
            AudioPlayer.release()
            SoundManager.release()
            mediaSession?.release()
            LocationHelper.stopLiveLocationUpdates(this, backgroundLocationListener)
        } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) try { wakeLock?.release() } catch (e: Exception) {}
        if (screenWakeLock?.isHeld == true) try { screenWakeLock?.release() } catch (e: Exception) {}
        WebSocketManager.disconnect()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("PTT Aktif", if (WebSocketManager.isConnected()) "Terhubung ke Server" else "Aplikasi berjalan di latar belakang")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIFICATION_ID, notification, type)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) { try { startForeground(NOTIFICATION_ID, notification) } catch (e2: Exception) {} }

        when (intent?.action) {
            ACTION_START_PTT -> {
                val fromVox = intent.getBooleanExtra("from_vox", false)
                performStartTalking(fromVox)
            }
            ACTION_STOP_PTT -> performStopTalking()
            ACTION_UPDATE_VOX -> checkVoxState()
            ACTION_VOX_RESTART -> {
                val delay = intent.getLongExtra(EXTRA_RESTART_DELAY_MS, 0L)
                handler.postDelayed({ checkVoxState() }, delay)
            }
            else -> {
                MediaButtonReceiver.handleIntent(mediaSession, intent)
                checkVoxState()
            }
        }
        return START_STICKY
    }
}
