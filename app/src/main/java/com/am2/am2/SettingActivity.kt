package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.am2.am2.databinding.ActivitySettingBinding
import java.util.*
import kotlin.math.sqrt

class SettingActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var isRecording = false
    private var mediaSession: MediaSessionCompat? = null
    private var volumeObserver: ContentObserver? = null
    private var lastVolume: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tempGatt: BluetoothGatt? = null
    private val TARGET_KEYWORDS = listOf("PTT-KEY", "noxgear", "PTT", "Caraka", "Walkie")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initBluetoothAdapter()

        checkScreenSizeCapabilities()
        loadSettings()
        setupListeners()
        // applyScreenSettings() sudah dipanggil oleh BaseActivity
    }

    private fun initBluetoothAdapter() {
        bluetoothAdapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    private fun checkScreenSizeCapabilities() {
        val dm = resources.displayMetrics
        val widthInches = dm.widthPixels.toDouble() / dm.xdpi
        val heightInches = dm.heightPixels.toDouble() / dm.ydpi
        val screenInches = sqrt(widthInches * widthInches + heightInches * heightInches)

        if (screenInches < 2.7) {
            binding.layoutInfoBoxSettingContainer.visibility = View.GONE
        } else {
            binding.layoutInfoBoxSettingContainer.visibility = View.VISIBLE
        }

        if (screenInches < 3.0) {
            binding.layoutVirtualPttContainer.visibility = View.GONE
        } else {
            binding.layoutVirtualPttContainer.visibility = View.VISIBLE
        }
    }

    private fun loadSettings() {
        val pttKey = prefs.getInt("ptt_key", -1)
        val pttMac = prefs.getString("ptt_mac", "00:00:00:00:00:00")
        val pttSource = prefs.getString("ptt_source", "Default")

        if (pttKey != -1) {
            updateKeyDisplay(pttKey)
        } else {
            binding.tvDetectedKey.text = "BELUM DI SET"
        }

        binding.tvSource.text = "Sumber: $pttSource"
        binding.tvMacAddress.text = "ID: $pttMac"

        binding.cbPttToggle.isChecked = prefs.getBoolean("ptt_toggle", false)
        binding.cbVox.isChecked = prefs.getBoolean("vox_enabled", false)
        binding.sbVoxSensitivity.progress = VoxSensitivity.progressFor(
            prefs.getInt("vox_threshold", VoxSensitivity.DEFAULT_THRESHOLD),
        )
        binding.cbShowVirtualPtt.isChecked = prefs.getBoolean("show_virtual_ptt", true)
        binding.cbShowInfoBox.isChecked = prefs.getBoolean("show_info_box", true)
        binding.cbStartOnBoot.isChecked = prefs.getBoolean("start_on_boot", false)
        binding.cbSoundPush.isChecked = prefs.getBoolean("sound_push", true)
        binding.cbSoundRx.isChecked = prefs.getBoolean("sound_rx", true)
        binding.cbKeepScreenOn.isChecked = prefs.getBoolean("keep_screen_on", false)
        binding.cbAutoWake.isChecked = prefs.getBoolean("auto_wake", false)

        val gatewayMode = prefs.getBoolean("gateway_mode", false)
        binding.cbGatewayMode.isChecked = gatewayMode

        val dtmfMode = prefs.getBoolean("dtmf_mode", false)
        binding.cbDtmfMode.isChecked = dtmfMode

        updateSoundSettingsVisibility(gatewayMode, dtmfMode)
    }

    private fun updateSoundSettingsVisibility(isGatewayActive: Boolean, isDtmfActive: Boolean) {
        if (isGatewayActive) {
            binding.cbSoundPush.visibility = View.GONE
            binding.cbSoundRx.visibility = View.GONE
            binding.cbDtmfMode.isEnabled = true
        } else {
            val visibility = if (isDtmfActive) View.GONE else View.VISIBLE
            binding.cbSoundPush.visibility = visibility
            binding.cbSoundRx.visibility = visibility
            binding.cbDtmfMode.isEnabled = true
        }
    }

    private fun updateKeyDisplay(keyCode: Int) {
        val keyName = when (keyCode) {
            0 -> "BLE / GATT"
            24 -> "VOL UP"
            25 -> "VOL DOWN"
            79 -> "HEADSET"
            228 -> "PTT"
            249, 248 -> "PTT SIDE"
            else -> {
                try {
                    KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                } catch (e: Exception) { "KEY_$keyCode" }
            }
        }
        binding.tvDetectedKey.text = "TOMBOL: $keyName ($keyCode)"
    }

    private fun setupListeners() {
        binding.btnSetPtt.setOnClickListener {
            startRecording()
        }

        binding.btnCancelRecording.setOnClickListener {
            stopRecording()
        }

        binding.btnResetToDefault.setOnClickListener {
            performResetToDefault()
        }

        binding.cbPttToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ptt_toggle", isChecked).apply()
        }

        binding.cbVox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vox_enabled", isChecked).apply()
            notifyVoxChanged()
        }

        /*
         * Sensitivity, not threshold, because that is what the operator is
         * choosing: further right means VOX keys on a quieter voice. The
         * recorder wants the amplitude to compare against, so the two are
         * inverses of each other and the conversion lives here.
         *
         * Written when the finger lifts rather than on every pixel, so a drag
         * across the bar is one preference write and one service intent.
         */
        binding.sbVoxSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                prefs.edit()
                    .putInt("vox_threshold", VoxSensitivity.thresholdFor(bar?.progress ?: 0))
                    .apply()
                notifyVoxChanged()
            }
        })

        binding.cbShowVirtualPtt.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_virtual_ptt", isChecked).apply()
        }

        binding.cbShowInfoBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_info_box", isChecked).apply()
        }

        binding.cbStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("start_on_boot", isChecked).apply()
        }

        binding.cbKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply()
            applyScreenSettings() // Memanggil fungsi di BaseActivity
        }

        binding.cbAutoWake.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_wake", isChecked).apply()
        }

        binding.cbSoundPush.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound_push", isChecked).apply()
        }

        binding.cbSoundRx.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound_rx", isChecked).apply()
        }

        binding.cbGatewayMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("gateway_mode", isChecked).apply()
            val dtmfActive = binding.cbDtmfMode.isChecked
            updateSoundSettingsVisibility(isChecked, dtmfActive)

            if (isChecked) {
                Toast.makeText(this, "Gateway Mode", Toast.LENGTH_SHORT).show()
            }
        }

        binding.cbDtmfMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dtmf_mode", isChecked).apply()
            val gatewayActive = binding.cbGatewayMode.isChecked
            updateSoundSettingsVisibility(gatewayActive, isChecked)

            if (!gatewayActive) {
                if (isChecked) {
                    binding.cbSoundPush.isChecked = false
                    binding.cbSoundRx.isChecked = false
                    prefs.edit().putBoolean("sound_push", false).putBoolean("sound_rx", false).apply()
                } else {
                    binding.cbSoundPush.isChecked = true
                    binding.cbSoundRx.isChecked = true
                    prefs.edit().putBoolean("sound_push", true).putBoolean("sound_rx", true).apply()
                }
            }
        }
    }

    /**
     * Tell a running PTTService that something about VOX changed.
     *
     * Without this the new value waits for the next service start, and on a
     * radio that is left switched on that is never.
     */
    private fun notifyVoxChanged() {
        try {
            val intent = Intent(this, PTTService::class.java).apply {
                action = PTTService.ACTION_UPDATE_VOX
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            SafeLog.e("SettingActivity", "Failed to notify PTTService: ${e.message}")
        }
    }

    private fun performResetToDefault() {
        stopRecording()
        prefs.edit().apply {
            putInt("ptt_key", -1)
            putString("ptt_source", "Default")
            putString("ptt_mac", "00:00:00:00:00:00")
        }.apply()
        loadSettings()
        Toast.makeText(this, "PTT direset ke Default", Toast.LENGTH_SHORT).show()
    }

    private fun startRecording() {
        if (!hasRequiredPermissions()) {
            checkPermissions()
            return
        }

        isRecording = true
        binding.layoutRecordingOverlay.visibility = View.VISIBLE

        mainHandler.postDelayed({
            try {
                lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                registerVolumeObserver()
                if (bluetoothAdapter?.isEnabled == true) {
                    manageMediaSession(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        startBleScan()
                    }
                }
            } catch (e: Exception) {
                stopRecording()
            }
        }, 200)
    }

    @SuppressLint("MissingPermission")
    private fun stopRecording() {
        isRecording = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stopBleScan()
        }
        unregisterVolumeObserver()
        manageMediaSession(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            tempGatt?.let { gatt ->
                try {
                    if (hasBluetoothConnectPermission()) gatt.disconnect()
                } catch (e: Exception) {
                } finally {
                    try { gatt.close() } catch (e: Exception) {}
                    tempGatt = null
                }
            }
        }

        runOnUiThread {
            binding.layoutRecordingOverlay.visibility = View.GONE
        }
    }

    private fun savePttKey(keyCode: Int, source: String = "Hardware Button", mac: String = "00:00:00:00:00:00") {
        var finalSource = source
        if (keyCode > 200 && source == "Hardware Button") finalSource = "POC Custom Button"

        prefs.edit().apply {
            putInt("ptt_key", keyCode)
            putString("ptt_source", finalSource)
            putString("ptt_mac", mac)
        }.apply()

        runOnUiThread {
            stopRecording()
            loadSettings()
            Toast.makeText(this, "Berhasil Terdeteksi: $finalSource", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (isRecording) {
            if (action == KeyEvent.ACTION_DOWN) {
                val restrictedKeys = listOf(
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SOFT_LEFT,
                    KeyEvent.KEYCODE_SOFT_RIGHT,
                    KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_POWER,
                    KeyEvent.KEYCODE_APP_SWITCH,
                    66, 23, 82
                )

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    stopRecording()
                    return true
                }

                if (keyCode in restrictedKeys) {
                    return true
                }

                val deviceMac = getDeviceAddressSafe(event.device)
                val source = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) "Volume Key" else "Hardware Button"
                savePttKey(keyCode, source, deviceMac)
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                if (currentFocus == binding.btnSetPtt) {
                    binding.btnSetPtt.performClick()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                currentFocus?.performClick()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun getDeviceAddressSafe(device: InputDevice?): String {
        if (device == null) return "00:00:00:00:00:00"
        
        // Cek apakah ini perangkat Bluetooth dengan mencoba mendapatkan alamatnya
        return try {
            val method = device.javaClass.getMethod("getAddress")
            val addr = method.invoke(device) as? String
            if (!addr.isNullOrEmpty() && addr != "00:00:00:00:00:00") {
                addr
            } else {
                // Untuk headset kabel atau tombol internal, kita gunakan MAC default
                // agar tidak terjebak pada ID dinamis yang bisa berubah.
                "00:00:00:00:00:00"
            }
        } catch (_: Exception) {
            "00:00:00:00:00:00"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return
        if (!hasBluetoothScanPermission()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, leScanCallback)
            } catch (e: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            bluetoothAdapter?.startLeScan(leScanCallbackOld)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return
        if (!hasBluetoothScanPermission()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            bluetoothAdapter?.stopLeScan(leScanCallbackOld)
        }
    }

    private val leScanCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(ct: Int, res: ScanResult) {
                if (!isRecording) return
                val device = res.device
                val deviceName = device.name ?: ""
                if (TARGET_KEYWORDS.any { deviceName.contains(it, ignoreCase = true) }) {
                    connectToTempGatt(device)
                }
            }
        }
    } else null

    private val leScanCallbackOld = if (Build.VERSION.SDK_INT in 18..20) {
        BluetoothAdapter.LeScanCallback { device, _, _ ->
            if (!isRecording) return@LeScanCallback
            @SuppressLint("MissingPermission")
            val deviceName = device.name ?: ""
            if (TARGET_KEYWORDS.any { deviceName.contains(it, ignoreCase = true) }) {
                connectToTempGatt(device)
            }
        }
    } else null

    private fun connectToTempGatt(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return
        if (tempGatt != null || !hasBluetoothConnectPermission()) return
        try {
            tempGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, recordingGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(this, false, recordingGattCallback)
            }
        } catch (e: Exception) {}
    }

    private val recordingGattCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (isRecording) gatt.close()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
                    val charUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                    val service = gatt.getService(serviceUuid)
                    val characteristic = service?.getCharacteristic(charUuid)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                val isPressed = if (data != null && data.isNotEmpty()) data[0].toInt() != 0 else false
                if (isPressed && isRecording) {
                    mainHandler.post {
                        @SuppressLint("MissingPermission")
                        val name = gatt.device.name ?: "BLE PTT"
                        savePttKey(0, "BLE ($name)", gatt.device.address)
                    }
                }
            }
        }
    } else null

    private fun manageMediaSession(enable: Boolean) {
        if (enable) {
            try {
                val mbr = ComponentName(packageName, "androidx.media.session.MediaButtonReceiver")
                mediaSession = MediaSessionCompat(this, "PTT_REC", mbr, null).apply {
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onMediaButtonEvent(intent: Intent): Boolean {
                            @Suppress("DEPRECATION")
                            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            if (event?.action == KeyEvent.ACTION_DOWN) {
                                savePttKey(event.keyCode, "Bluetooth Media Button", getDeviceAddressSafe(event.device))
                                return true
                            }
                            return super.onMediaButtonEvent(intent)
                        }
                    })
                    isActive = true
                }
            } catch (e: Exception) {}
        } else {
            mediaSession?.let { it.isActive = false; it.release() }
            mediaSession = null
        }
    }

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        volumeObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                try {
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (isRecording && cur != lastVolume) {
                        savePttKey(if (cur > lastVolume) 24 else 25, "Volume Key")
                    }
                    lastVolume = cur
                } catch (_: Exception) {}
            }
        }
        contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver!!)
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        volumeObserver = null
    }

    private fun hasBluetoothScanPermission(): Boolean = ContextCompat.checkSelfPermission(this, if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasBluetoothConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
