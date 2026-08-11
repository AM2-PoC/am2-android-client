package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Toast
import com.am2.am2.databinding.ActivityMapsBinding
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.sqrt

class MapsActivity : BaseActivity() {

    private lateinit var binding: ActivityMapsBinding
    private val prefs: SharedPreferences by lazy { getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE) }
    private val TAG = "MapsActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val userMarkers = mutableMapOf<String, Marker>()
    
    private var isReporting = false
    private var liveLocationListener: Any? = null
    
    private var pttHardwareKey: Int = -1
    private var pttToggleEnabled = false
    private var isPttPressed = false

    private var currentState = "IDLE"
    private val RESET_FOCUS_DELAY = 10000L
    private var isUserInteracting = false
    private var isInitialFocusDone = false

    private var mapWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val internalCache = File(filesDir, "osmdroid")
        if (!internalCache.exists()) internalCache.mkdirs()

        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = internalCache
        osmConfig.osmdroidTileCache = File(internalCache, "tiles")
        
        val osmPrefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        osmConfig.load(this, osmPrefs)
        osmConfig.osmdroidBasePath = internalCache
        osmConfig.osmdroidTileCache = File(internalCache, "tiles")
        osmConfig.save(this, osmPrefs)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)

        setupMap()
        setupObservers()
        setupClickListeners()
        applyUiVisibility()
        applyScreenSettings()
        
        startLocationReporting()
    }

    override fun applyScreenSettings() {
        super.applyScreenSettings()
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(15.0)
        
        val lastPoint = LocationHelper.getCachedLocation(this)
        binding.map.controller.setCenter(lastPoint)
        binding.map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        
        binding.map.isFocusable = true
        binding.map.isFocusableInTouchMode = true
        binding.map.requestFocus()

        binding.map.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                isUserInteracting = true
            }
            v.performClick()
            false
        }
    }

    private fun applyUiVisibility() {
        val dm = resources.displayMetrics
        val widthInches = dm.widthPixels.toDouble() / dm.xdpi
        val heightInches = dm.heightPixels.toDouble() / dm.ydpi
        val screenInches = sqrt(widthInches * widthInches + heightInches * heightInches)

        val showVirtualPttPref = prefs.getBoolean("show_virtual_ptt", true)
        binding.layoutPttContainer.visibility = if (showVirtualPttPref && screenInches >= 3.0) View.VISIBLE else View.GONE
    }

    private fun updatePttUi() {
        if (isFinishing) return

        val isMeTalking = WebSocketManager.isTalking.value ?: false
        val speakers = WebSocketManager.activeSpeakersList.value
        val hasSpeakers = !speakers.isNullOrEmpty()
        val isRxOnly = WebSocketManager.isRxOnly.value ?: false
        val isPtp = WebSocketManager.ptpTargetId.value != null
        val voxEnabled = prefs.getBoolean("vox_enabled", false)

        binding.btnPtt.isPressed = isMeTalking
        binding.btnPtt.isActivated = hasSpeakers && !isMeTalking

        val iconColor = when {
            isMeTalking -> Color.RED
            hasSpeakers -> Color.GREEN
            else -> Color.GRAY
        }
        binding.btnPttIcon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

        if ((isRxOnly || voxEnabled) && !isPtp) {
            binding.btnPtt.alpha = 0.5f
        } else {
            binding.btnPtt.alpha = 1.0f
        }

        if (isMeTalking || hasSpeakers) {
            if (binding.btnPttBackground.animation == null) {
                val rotateAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_infinite)
                binding.btnPttBackground.startAnimation(rotateAnim)
            }
        } else {
            binding.btnPttBackground.clearAnimation()
        }
    }

    private fun setupObservers() {
        WebSocketManager.usersOnline.observe(this) { users ->
            handleOnlineUsers(users)
        }

        WebSocketManager.isTalking.observe(this) { talking ->
            if (pttToggleEnabled) isPttPressed = talking
            
            if (talking) {
                updateTalkingState("TX", WebSocketManager.myUserName ?: "Anda")
                handleAutoWake(true)
            } else {
                checkIdleState()
            }
            updatePttUi()
        }

        WebSocketManager.activeSpeakersList.observe(this) { speakers ->
            if (speakers.isNotEmpty() && !WebSocketManager.isTalking.value!!) {
                updateTalkingState("RX", speakers.last())
                handleAutoWake(true)
            } else if (speakers.isEmpty()) {
                checkIdleState()
            }
            updatePttUi()
        }

        WebSocketManager.isRxOnly.observe(this) { _ ->
            updatePttUi()
        }
    }

    private fun handleAutoWake(isTalking: Boolean) {
        val autoWake = prefs.getBoolean("auto_wake", false)
        if (!autoWake || !isTalking) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
        
        if (!isInteractive) {
            if (mapWakeLock == null) {
                @Suppress("DEPRECATION")
                mapWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TIK:WakeLockMaps"
                )
            }
            try {
                if (mapWakeLock?.isHeld == false) mapWakeLock?.acquire(3000L)
            } catch (e: Exception) {
                SafeLog.e(TAG, "Failed to acquire wakeLock")
            }
        }
    }

    private fun checkIdleState() {
        val isMeTalking = WebSocketManager.isTalking.value ?: false
        val isSomeoneElseTalking = WebSocketManager.activeSpeakersList.value?.isNotEmpty() ?: false
        
        if (!isMeTalking && !isSomeoneElseTalking) {
            updateTalkingState("IDLE", "")
        }
    }

    private fun updateTalkingState(state: String, username: String) {
        if (isFinishing) return
        currentState = state
        mainHandler.removeCallbacksAndMessages("IDLE_SYNC")

        when (state) {
            "TX" -> {
                binding.cardInfoTx.visibility = View.VISIBLE
                binding.txtSpeakerName.text = "\uD83D\uDCE2 You are Speaking"
                binding.txtSpeakerName.setTextColor(Color.parseColor("#FF5252"))
                
                val marker = userMarkers["me"]
                marker?.let {
                    val addr = LocationHelper.getAddressLocally(this, it.position.latitude, it.position.longitude)
                    binding.txtSpeakerAddress.text = addr
                    if (!isUserInteracting) {
                        binding.map.controller.animateTo(it.position)
                        if (binding.map.zoomLevelDouble < 18.0) binding.map.controller.setZoom(18.0)
                    }
                }
            }
            "RX" -> {
                binding.cardInfoTx.visibility = View.VISIBLE
                binding.txtSpeakerName.text = "\uD83D\uDCE2 $username is Speaking"
                binding.txtSpeakerName.setTextColor(Color.parseColor("#4CAF50"))
                
                val marker = userMarkers.values.find { 
                    val title = it.title ?: ""
                    title.contains(username, ignoreCase = true) || title.equals(username, ignoreCase = true)
                }
                
                if (marker != null) {
                    val addr = LocationHelper.getAddressLocally(this, marker.position.latitude, marker.position.longitude)
                    binding.txtSpeakerAddress.text = addr
                    if (!isUserInteracting) {
                        binding.map.controller.animateTo(marker.position)
                        if (binding.map.zoomLevelDouble < 18.0) binding.map.controller.setZoom(18.0)
                    }
                }
            }
            else -> {
                binding.cardInfoTx.visibility = View.GONE
                
                mainHandler.postAtTime({
                    if (!isFinishing && !isUserInteracting) {
                        centerOnMe()
                    }
                }, "IDLE_SYNC", SystemClock.uptimeMillis() + RESET_FOCUS_DELAY)
            }
        }
        binding.map.invalidate()
    }

    private fun centerOnMe() {
        userMarkers["me"]?.let {
            binding.map.controller.animateTo(it.position)
        }
    }

    private fun handleRemoteLocationUpdate(userObj: JSONObject) {
        val userId = userObj.optString("id")
        val username = userObj.optString("name")
        val lat = userObj.optDouble("latitude", 0.0)
        val lng = userObj.optDouble("longitude", 0.0)

        if (lat == 0.0 && lng == 0.0) return

        val point = GeoPoint(lat, lng)
        val isMe = (userId == WebSocketManager.myUserId || username == WebSocketManager.myUserName)
        val markerKey = if (isMe) "me" else if (userId.isNotEmpty()) userId else username
        
        var marker = userMarkers[markerKey]
        val fastAddr = LocationHelper.getAddressLocally(this, lat, lng)

        if (marker == null) {
            marker = Marker(binding.map)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = if (isMe) "Saya (Anda)" else username
            marker.snippet = fastAddr
            marker.infoWindow = null 
            binding.map.overlays.add(marker)
            userMarkers[markerKey] = marker
        }

        marker.position = point
        
        if (!isUserInteracting) {
            val isSpeaker = stateMatchesUser(markerKey, username)
            val shouldFocusMeFirst = !isInitialFocusDone && isMe && currentState == "IDLE"
            
            if (isSpeaker || shouldFocusMeFirst) {
                binding.map.controller.animateTo(point)
                if (binding.map.zoomLevelDouble < 17.5) binding.map.controller.setZoom(17.5)
                isInitialFocusDone = true
                if (isSpeaker) binding.txtSpeakerAddress.text = fastAddr
            }
        }

        LocationHelper.performGeocode(this, lat, lng) { _, _, _, freshAddress ->
            if (!isFinishing && freshAddress != null) {
                marker.snippet = freshAddress
                if (stateMatchesUser(markerKey, username)) {
                    binding.txtSpeakerAddress.text = freshAddress
                }
            }
        }

        binding.map.invalidate()
    }

    private fun stateMatchesUser(key: String, name: String): Boolean {
        return (currentState == "TX" && key == "me") || 
               (currentState == "RX" && WebSocketManager.activeSpeakersList.value?.contains(name) == true)
    }

    private fun handleOnlineUsers(users: JSONArray?) {
        if (users == null) return
        
        val activeKeys = mutableSetOf<String>("me")
        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            val userId = user.optString("id")
            val name = user.optString("name")
            
            val key = if (userId == WebSocketManager.myUserId || name == WebSocketManager.myUserName) "me" else if (userId.isNotEmpty()) userId else name
            activeKeys.add(key)
            
            handleRemoteLocationUpdate(user)
        }

        val iterator = userMarkers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!activeKeys.contains(entry.key)) {
                binding.map.overlays.remove(entry.value)
                iterator.remove()
            }
        }
        binding.map.invalidate()
    }

    private fun startLocationReporting() {
        if (isReporting) return
        isReporting = true
        
        liveLocationListener = LocationHelper.startLiveLocationUpdates(this) { lat, lon, acc, _ ->
            if (lat != 0.0 && !isFinishing) {
                WebSocketManager.updateLocation(lat, lon, acc)
                
                val meJson = JSONObject().apply {
                    put("id", WebSocketManager.myUserId)
                    put("name", WebSocketManager.myUserName)
                    put("latitude", lat)
                    put("longitude", lon)
                }
                runOnUiThread { handleRemoteLocationUpdate(meJson) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        binding.btnMyLocation.setOnClickListener {
            isUserInteracting = false
            centerOnMe()
            if (binding.map.zoomLevelDouble < 17.0) binding.map.controller.setZoom(17.0)
        }

        binding.btnPtt.setOnTouchListener { v, event ->
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null
            val voxEnabled = prefs.getBoolean("vox_enabled", false)

            if ((isRxOnly || voxEnabled) && !isPtpActive) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val msg = if (voxEnabled) "Mode VOX Aktif: Tombol PTT Dinonaktifkan" else "Mode RX Only: Anda tidak dapat berbicara"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (pttToggleEnabled) {
                        if (!isPttPressed) { v.isPressed = true; startPtt() }
                        else { v.isPressed = false; stopPtt() }
                    } else { v.isPressed = true; startPtt() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!pttToggleEnabled) { v.isPressed = false; stopPtt() }
                }
            }
            true
        }
    }

    private fun startPtt() {
        if (isPttPressed) return
        isPttPressed = true
        val intent = Intent(this, PTTService::class.java).apply {
            action = PTTService.ACTION_START_PTT
        }
        startService(intent)
    }

    private fun stopPtt() {
        if (!isPttPressed) return
        isPttPressed = false
        val intent = Intent(this, PTTService::class.java).apply {
            action = PTTService.ACTION_STOP_PTT
        }
        startService(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pttHardwareKey != -1 && keyCode == pttHardwareKey) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null
            val voxEnabled = prefs.getBoolean("vox_enabled", false)

            if ((isRxOnly || voxEnabled) && !isPtpActive) {
                if (event?.repeatCount == 0) {
                    val msg = if (voxEnabled) "Mode VOX Aktif: Tombol PTT Dinonaktifkan" else "Mode RX Only"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                return true
            }

            if (event?.repeatCount == 0) {
                if (pttToggleEnabled) {
                    if (!isPttPressed) {
                        binding.btnPtt.isPressed = true
                        startPtt()
                    } else {
                        binding.btnPtt.isPressed = false
                        stopPtt()
                    }
                } else {
                    binding.btnPtt.isPressed = true
                    startPtt()
                }
                
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, 0)
                }
                return true
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (pttHardwareKey != -1 && keyCode == pttHardwareKey) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null

            if ((isRxOnly || prefs.getBoolean("vox_enabled", false)) && !isPtpActive) return true

            if (!pttToggleEnabled) {
                binding.btnPtt.isPressed = false
                stopPtt()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        updatePttUi()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        if (mapWakeLock?.isHeld == true) {
            try { mapWakeLock?.release() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        LocationHelper.stopLiveLocationUpdates(this, liveLocationListener)
        isReporting = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
