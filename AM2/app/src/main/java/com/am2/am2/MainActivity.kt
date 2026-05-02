package com.am2.am2

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
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.android.gms.security.ProviderInstaller
import com.am2.am2.databinding.ActivityMainBinding
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var activityStartTime: Long = 0
    private var isTimerRunning = false
    private var isPttPressed = false
    private var pttHardwareKey: Int = -1
    private var pttToggleEnabled = false

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance().time
            binding.tvDigitalClock.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
            binding.tvDate.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(now)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateSecurityProvider()
        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)

        binding.tvUserName.text = WebSocketManager.myUserName ?: "User"
        binding.tvChannelName.isSelected = true
        binding.tvLastSpeaking.isSelected = true

        binding.tvChannelName.setOnClickListener {
            startActivity(Intent(this, GroupActivity::class.java))
        }

        updateWeatherWithLocation()
        setupSocketListeners()
        setupNetworkObserver()
        setupBackPressedHandling()
        startPttService()

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

            if (WebSocketManager.currentChannelSlug.isNullOrEmpty() && !isPtpActive) return@setOnTouchListener false

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

        binding.btnMenu.setOnClickListener { startActivity(Intent(this, MenuActivity::class.java)) }
        binding.ivShortcutMaps.setOnClickListener {
            if (WebSocketManager.isMapsEnabled.value == true) startActivity(Intent(this, MapsActivity::class.java))
            else Toast.makeText(this, "Fitur Maps dinonaktifkan", Toast.LENGTH_SHORT).show()
        }
        binding.ivShortcutUsers.setOnClickListener {
            if (WebSocketManager.isPtpEnabled.value == true) startActivity(Intent(this, UserActivity::class.java))
            else Toast.makeText(this, "Fitur User dinonaktifkan", Toast.LENGTH_SHORT).show()
        }
        binding.ivShortcutVideo.setOnClickListener {
            if (WebSocketManager.isVideoEnabled.value == true) startActivity(Intent(this, VideoActivity::class.java))
            else Toast.makeText(this, "Fitur Video dinonaktifkan", Toast.LENGTH_SHORT).show()
        }

        applyUiVisibility()
    }

    private fun setupBackPressedHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (WebSocketManager.ptpTargetId.value != null) {
                    WebSocketManager.endPtp()
                    Toast.makeText(this@MainActivity, "Kembali ke mode Channel", Toast.LENGTH_SHORT).show()
                } else moveTaskToBack(true)
            }
        })
    }

    private fun updateSecurityProvider() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            thread { try { ProviderInstaller.installIfNeeded(applicationContext) } catch (e: Exception) {} }
        }
    }

    private fun startPttService() {
        val serviceIntent = Intent(this, PTTService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)
        updateNetworkUI()
        applyUiVisibility()
        updatePttUiState()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun applyUiVisibility() {
        val dm = resources.displayMetrics
        val screenInches = sqrt(Math.pow(dm.widthPixels.toDouble() / dm.xdpi, 2.0) + Math.pow(dm.heightPixels.toDouble() / dm.ydpi, 2.0))
        binding.layoutInfoBox.visibility = if (prefs.getBoolean("show_info_box", true) && screenInches >= 2.7) View.VISIBLE else View.GONE
        binding.layoutPttContainer.visibility = if (prefs.getBoolean("show_virtual_ptt", true) && screenInches >= 3.0) View.VISIBLE else View.GONE

        WebSocketManager.isMapsEnabled.observe(this) { enabled -> binding.ivShortcutMaps.setColorFilter(if (enabled) Color.GREEN else Color.GRAY, PorterDuff.Mode.SRC_IN) }
        WebSocketManager.isPtpEnabled.observe(this) { enabled -> binding.ivShortcutUsers.setColorFilter(if (enabled) Color.GREEN else Color.GRAY, PorterDuff.Mode.SRC_IN) }
        WebSocketManager.isVideoEnabled.observe(this) { enabled -> binding.ivShortcutVideo.setColorFilter(if (enabled) Color.GREEN else Color.GRAY, PorterDuff.Mode.SRC_IN) }
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

            if (event?.repeatCount == 0 && (!WebSocketManager.currentChannelSlug.isNullOrEmpty() || isPtpActive)) {
                if (pttToggleEnabled) {
                    if (!isPttPressed) { binding.btnPtt.isPressed = true; startPtt() }
                    else { binding.btnPtt.isPressed = false; stopPtt() }
                } else { binding.btnPtt.isPressed = true; startPtt() }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, 0)
                }
                return true
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (pttHardwareKey != -1 && keyCode == pttHardwareKey) {
            if (!pttToggleEnabled) { binding.btnPtt.isPressed = false; stopPtt() }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startPtt() {
        if (isPttPressed) return
        isPttPressed = true
        startUnifiedTimer()
        startService(Intent(this, PTTService::class.java).apply { action = PTTService.ACTION_START_PTT })
    }

    private fun stopPtt() {
        if (!isPttPressed) return
        isPttPressed = false
        startService(Intent(this, PTTService::class.java).apply { action = PTTService.ACTION_STOP_PTT })
        checkActivityAndStopTimer()
    }

    private fun setupSocketListeners() {
        WebSocketManager.myUserNameLiveData.observe(this) { name -> binding.tvUserName.text = name ?: "User" }
        WebSocketManager.talkingStatus.observe(this) { status -> updateStatusUI(status) }
        WebSocketManager.channelName.observe(this) { name -> binding.tvChannelName.text = name; binding.tvChannelName.isSelected = true }
        WebSocketManager.ptpTargetId.observe(this) { targetId ->
            updatePttUiState()
            if (targetId != null) {
                binding.tvChannelName.setTextColor(Color.parseColor("#FFA500"))
            } else {
                binding.tvChannelName.setTextColor(Color.WHITE)
            }
        }
        WebSocketManager.lastSpeaker.observe(this) { speaker -> binding.tvLastSpeaking.text = speaker; binding.tvLastSpeaking.isSelected = true }
        WebSocketManager.isTalking.observe(this) { talking ->
            if (pttToggleEnabled) {
                isPttPressed = talking
                if (talking) startUnifiedTimer() else checkActivityAndStopTimer()
            }
            updatePttUiState()
        }
        WebSocketManager.activeSpeakersList.observe(this) { speakers ->
            if (speakers.isNotEmpty()) startUnifiedTimer() else checkActivityAndStopTimer()
            updatePttUiState()
        }
        WebSocketManager.isRxOnly.observe(this) { _ ->
            updatePttUiState()
        }
        WebSocketManager.navigateToVideo.observe(this) { navigate ->
            if (navigate) { WebSocketManager.resetNavigation(); startActivity(Intent(this, VideoActivity::class.java)) }
        }
    }

    private fun updatePttUiState() {
        val isTalking = WebSocketManager.isTalking.value ?: false
        val hasSpeakers = WebSocketManager.activeSpeakersList.value?.isNotEmpty() ?: false
        val isRxOnly = WebSocketManager.isRxOnly.value == true
        val isPtpActive = WebSocketManager.ptpTargetId.value != null
        val voxEnabled = prefs.getBoolean("vox_enabled", false)

        binding.btnPtt.isPressed = isTalking
        binding.btnPtt.isActivated = hasSpeakers && !isTalking
        
        // Redupkan tombol jika RX Only atau VOX Aktif (kecuali sedang Private Call)
        binding.btnPtt.alpha = if ((isRxOnly || voxEnabled) && !isPtpActive) 0.5f else 1.0f

        val color = when {
            isTalking -> Color.RED
            hasSpeakers -> Color.GREEN
            else -> Color.GRAY
        }
        binding.btnPttIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        if (isTalking || hasSpeakers) {
            if (binding.btnPttBackground.animation == null) {
                binding.btnPttBackground.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_infinite))
            }
        } else binding.btnPttBackground.clearAnimation()
    }

    private fun startUnifiedTimer() {
        if (isTimerRunning) return
        isTimerRunning = true
        activityStartTime = System.currentTimeMillis()
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun checkActivityAndStopTimer() {
        handler.postDelayed({
            if (WebSocketManager.activeSpeakersList.value?.isEmpty() == true && !isPttPressed && WebSocketManager.isTalking.value == false) {
                isTimerRunning = false
                handler.removeCallbacks(timerRunnable)
                binding.tvPttTimer.text = "00:00"
            }
        }, 500)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isTimerRunning) return
            val elapsed = (System.currentTimeMillis() - activityStartTime) / 1000
            binding.tvPttTimer.text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStatusUI(status: String) {
        binding.tvStatus.text = status
        val isIdle = status.equals("IDLE", ignoreCase = true) || status.isEmpty()
        binding.tvStatus.ellipsize = if (isIdle) null else TextUtils.TruncateAt.MARQUEE
        binding.tvStatus.isSelected = !isIdle
        when {
            status.contains("You are Speaking", ignoreCase = true) || status.contains("Private to", ignoreCase = true) -> binding.tvStatus.setTextColor(Color.RED)
            status.contains("is Speaking", ignoreCase = true) || status.contains("PRIVATE from", ignoreCase = true) -> binding.tvStatus.setTextColor(Color.GREEN)
            status.contains("READY Private", ignoreCase = true) -> binding.tvStatus.setTextColor(Color.parseColor("#FFA500"))
            else -> binding.tvStatus.setTextColor(Color.WHITE)
        }
    }

    private fun setupNetworkObserver() {
        NetworkManager.networkInfo.observe(this) { updateNetworkUI() }
        NetworkManager.networkIcon.observe(this) { updateNetworkUI() }
        NetworkManager.networkColor.observe(this) { updateNetworkUI() }
    }

    private fun updateNetworkUI() {
        val info = NetworkManager.networkInfo.value ?: "OFFLINE"
        val iconRes = NetworkManager.networkIcon.value ?: R.drawable.ic_no_network
        val color = NetworkManager.networkColor.value ?: Color.WHITE
        binding.tvProvider.text = info
        binding.tvProvider.setTextColor(Color.WHITE)
        ContextCompat.getDrawable(this, iconRes)?.let {
            it.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
            binding.tvProvider.setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
        }
    }

    private fun updateWeatherWithLocation() {
        LocationHelper.getLastKnownLocation(this) { lat, lon, _, _ -> fetchWeatherData(if (lat != 0.0) lat else -6.21, if (lon != 0.0) lon else 106.85) }
    }

    private fun fetchWeatherData(lat: Double, lon: Double) {
        thread {
            try {
                val res = LocationHelper.okHttpClient.newCall(Request.Builder().url("http://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true").build()).execute().body()?.string() ?: ""
                val current = JSONObject(res).getJSONObject("current_weather")
                val temp = current.getDouble("temperature")
                val code = current.getInt("weathercode")
                val weatherDesc = when (code) { 0 -> "Cerah"; 1, 2, 3 -> "Berawan"; 45, 48 -> "Kabut"; 51, 53, 55 -> "Gerimis"; 61, 63, 65, 71, 73, 75 -> "Hujan"; 80, 81, 82 -> "Hujan Lebat"; 95, 96, 99 -> "Badai Petir"; else -> "Cerah" }
                val iconRes = when (code) { 0 -> R.drawable.ic_weather_sunny; 1, 2, 3 -> R.drawable.ic_weather_cloudy; 45, 48 -> R.drawable.ic_weather_fog; 51, 53, 55 -> R.drawable.ic_weather_drizzle; 61, 63, 65, 71, 73, 75, 80, 81, 82 -> R.drawable.ic_weather_rainy; 95, 96, 99 -> R.drawable.ic_weather_thunder; else -> R.drawable.ic_weather_sunny }
                runOnUiThread {
                    binding.tvWeather.text = "${temp.toInt()}°C $weatherDesc"
                    ContextCompat.getDrawable(this, iconRes)?.let { it.mutate().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN); binding.tvWeather.setCompoundDrawablesWithIntrinsicBounds(it, null, null, null) }
                }
            } catch (e: Exception) {}
        }
    }
}
