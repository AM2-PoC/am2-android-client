package com.am2.am2

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object WebSocketManager {
    private const val TAG = "WebSocketManager"
    private const val SERVER_URL = "ws://global.poc-id.my.id:5000"
    private const val PREFS_NAME = "AM2_PREFS"

    private const val DEBOUNCE_DISCONNECT_MS = 5000L

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var prefs: SharedPreferences? = null
    private var deviceId: String? = null
    private var appContext: Context? = null
    private var lastSentLat: Double = 0.0
    private var lastSentLon: Double = 0.0

    @Volatile private var isConnecting = false
    @Volatile private var reconnectDelay = 2000L
    private const val MAX_RECONNECT_DELAY = 10000L
    private var disconnectDebounceRunnable: Runnable? = null
    @Volatile private var actualSocketConnected = false

    var myUserId: String? = null
    var myUserName: String? = null
        set(value) {
            field = value
            _myUserNameLiveData.postValue(value)
        }

    private val _myUserNameLiveData = MutableLiveData<String?>()
    val myUserNameLiveData: LiveData<String?> = _myUserNameLiveData

    var currentChannelSlug: String? = null

    private var savedUsername: String? = null
    private var savedPassword: String? = null

    @Volatile private var isAuthorizedSession = false
    @Volatile private var isAuthenticatedOnCurrentSocket = false

    private val _availableChannels = MutableLiveData<JSONArray>(JSONArray())
    val availableChannels: LiveData<JSONArray> = _availableChannels

    private val _usersOnline = MutableLiveData<JSONArray>(JSONArray())
    val usersOnline: LiveData<JSONArray> = _usersOnline

    private val _activeSpeakersList = MutableLiveData<Set<String>>(emptySet())
    val activeSpeakersList: LiveData<Set<String>> = _activeSpeakersList

    private val activeSpeakers = Collections.synchronizedSet(LinkedHashSet<String>())
    private val speakerLastSeen = mutableMapOf<String, Long>()
    private var lastSpeakersBeforeEmpty = emptySet<String>()
    private var wasSomeoneElseTalking = false

    private val _activeVideoStreamers = MutableLiveData<Set<String>>(emptySet())
    val activeVideoStreamers: LiveData<Set<String>> = _activeVideoStreamers

    private val _incomingVideoFrame = MutableLiveData<Pair<String, ByteArray>>()
    val incomingVideoFrame: LiveData<Pair<String, ByteArray>> = _incomingVideoFrame

    // --- ENUM STATUS KOMUNIKASI UNTUK UI ---
    enum class CommState { OFFLINE, RECONNECTING, IDLE, TX, RX, CALLING }
    private val _communicationState = MutableLiveData<CommState>(CommState.OFFLINE)
    val communicationState: LiveData<CommState> = _communicationState

    private val _talkingStatus = MutableLiveData<String>("OFFLINE")
    val talkingStatus: LiveData<String> = _talkingStatus

    private val _channelName = MutableLiveData<String>("Connecting...")
    val channelName: LiveData<String> = _channelName
    private var lastChannelName: String = "Connecting..."

    private val _lastSpeaker = MutableLiveData<String>("Terakhir: -")
    val lastSpeaker: LiveData<String> = _lastSpeaker

    private val _connectionStatus = MutableLiveData<Boolean>(false)
    val connectionStatus: LiveData<Boolean> = _connectionStatus

    private val _isTalking = MutableLiveData<Boolean>(false)
    val isTalking: LiveData<Boolean> = _isTalking

    private val _isCommunicationActive = MutableLiveData<Boolean>(false)
    val isCommunicationActive: LiveData<Boolean> = _isCommunicationActive

    private val _isRxOnly = MutableLiveData<Boolean>(false)
    val isRxOnly: LiveData<Boolean> = _isRxOnly

    private val _isVideoEnabled = MutableLiveData<Boolean>(true)
    val isVideoEnabled: LiveData<Boolean> = _isVideoEnabled

    private val _isPtpEnabled = MutableLiveData<Boolean>(true)
    val isPtpEnabled: LiveData<Boolean> = _isPtpEnabled

    private val _isMapsEnabled = MutableLiveData<Boolean>(true)
    val isMapsEnabled: LiveData<Boolean> = _isMapsEnabled

    private val _duplexMode = MutableLiveData<String>("HALF DUPLEX")
    val duplexMode: LiveData<String> = _duplexMode

    private val _loginEvent = MutableLiveData<LoginEvent?>()
    val loginEvent: LiveData<LoginEvent?> = _loginEvent

    @Volatile private var internalPtpTargetId: String? = null
    @Volatile private var internalPtpTargetName: String? = null
    @Volatile private var ptpRequestPending = false

    private val _ptpTargetId = MutableLiveData<String?>(null)
    val ptpTargetId: LiveData<String?> = _ptpTargetId

    private val _ptpTargetName = MutableLiveData<String?>(null)
    val ptpTargetName: LiveData<String?> = _ptpTargetName

    private val _isPrivateRx = MutableLiveData<Boolean>(false)
    val isPrivateRx: LiveData<Boolean> = _isPrivateRx

    private val _isPtpVideo = MutableLiveData<Boolean>(false)
    val isPtpVideo: LiveData<Boolean> = _isPtpVideo

    private val _navigateToVideo = MutableLiveData<Boolean>(false)
    val navigateToVideo: LiveData<Boolean> = _navigateToVideo

    private val _ptpHandshakeEvent = MutableLiveData<PtpHandshakeEvent?>(null)
    val ptpHandshakeEvent: LiveData<PtpHandshakeEvent?> = _ptpHandshakeEvent

    sealed class PtpHandshakeEvent {
        data class Requesting(val userName: String) : PtpHandshakeEvent()
        data class Failed(val message: String) : PtpHandshakeEvent()
        object Success : PtpHandshakeEvent()
    }

    sealed class LoginEvent {
        data class Success(val userId: String, val username: String) : LoginEvent()
        data class Error(val message: String) : LoginEvent()
        object ForceLogout : LoginEvent()
    }

    @Volatile
    private var internalIsTalking = false
    private var mapListener: OnMessageListener? = null

    private val pttHandler = Handler(Looper.getMainLooper())
    private var lastPttStartTime: Long = 0
    private var lastPttEndTime: Long = 0

    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            updateTalkingStatusUI()
            if (activeSpeakers.isEmpty() && !AudioPlayer.isActuallyPlaying()) {
                // Done
            } else {
                pttHandler.postDelayed(this, 200)
            }
        }
    }

    private val ptpTimeoutRunnable = Runnable {
        if (ptpRequestPending) {
            ptpRequestPending = false
            _ptpHandshakeEvent.postValue(PtpHandshakeEvent.Failed("Permintaan waktu habis. Personel tidak merespon."))
            updateTalkingStatusUI()
        }
    }

    interface OnMessageListener {
        fun onMessage(text: String?)
    }

    init {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        savedUsername = prefs?.getString("username", null)
        savedPassword = prefs?.getString("password", null)

        if (savedUsername.isNullOrEmpty() || savedPassword.isNullOrEmpty()) {
            try {
                val file = File(context.filesDir, "cred.txt")
                if (file.exists()) {
                    val content = file.readText()
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        savedUsername = parts[0]
                        savedPassword = parts[1]
                    }
                }
            } catch (e: Exception) {}
        }

        currentChannelSlug = prefs?.getString("last_channel_slug", null)

        val startOnBoot = prefs?.getBoolean("start_on_boot", false) ?: false
        isAuthorizedSession = startOnBoot && !savedUsername.isNullOrEmpty() && !savedPassword.isNullOrEmpty()

        val lastSavedName = prefs?.getString("last_channel_name", "Connecting...") ?: "Connecting..."
        lastChannelName = lastSavedName
        _channelName.postValue(lastChannelName)
    }

    private fun String.toTruncatedId(): Int {
        return this.toLongOrNull()?.toInt() ?: this.hashCode()
    }

    fun setMapListener(listener: OnMessageListener?) {
        this.mapListener = listener
    }

    private fun resetTalkingState() {
        synchronized(activeSpeakers) {
            activeSpeakers.clear()
            speakerLastSeen.clear()
            lastSpeakersBeforeEmpty = emptySet()
            wasSomeoneElseTalking = false
        }
        AudioPlayer.stop()
    }

    fun setPtpTarget(userId: String?, userName: String?) {
        pttHandler.removeCallbacks(ptpTimeoutRunnable)
        ptpRequestPending = false
        internalPtpTargetId = userId
        internalPtpTargetName = userName
        _ptpTargetId.postValue(userId)
        _ptpTargetName.postValue(userName)

        if (userName != null) {
            _channelName.postValue("PRIVATE WITH $userName")
            _ptpHandshakeEvent.postValue(PtpHandshakeEvent.Success)
        } else {
            _channelName.postValue(lastChannelName)
            _isPtpVideo.postValue(false)
        }

        resetTalkingState()
        updateTalkingStatusUI()
    }

    fun startPtpWith(userId: String, userName: String) {
        if (_isPtpEnabled.value == false) return
        ptpRequestPending = true
        internalPtpTargetName = userName
        _isPtpVideo.postValue(false)
        _ptpHandshakeEvent.postValue(PtpHandshakeEvent.Requesting(userName))
        updateTalkingStatusUI()
        emit("request_ptp", JSONObject().put("target_id", userId))

        pttHandler.removeCallbacks(ptpTimeoutRunnable)
        pttHandler.postDelayed(ptpTimeoutRunnable, 10000)
    }

    fun startPtpVideoWith(userId: String, userName: String) {
        if (_isVideoEnabled.value == false || _isPtpEnabled.value == false) return
        ptpRequestPending = true
        internalPtpTargetName = userName
        _isPtpVideo.postValue(true)
        _ptpHandshakeEvent.postValue(PtpHandshakeEvent.Requesting(userName))
        updateTalkingStatusUI()
        emit("request_ptp_video", JSONObject().put("target_id", userId))

        pttHandler.removeCallbacks(ptpTimeoutRunnable)
        pttHandler.postDelayed(ptpTimeoutRunnable, 10000)
    }

    fun endPtp() {
        pttHandler.removeCallbacks(ptpTimeoutRunnable)
        val targetId = internalPtpTargetId
        if (targetId != null) {
            emit("cancel_ptp", JSONObject().put("target_id", targetId))
        }
        setPtpTarget(null, null)
    }

    fun clearPtpHandshakeEvent() {
        _ptpHandshakeEvent.postValue(null)
    }

    fun connect() {
        if (isConnecting) return
        if (actualSocketConnected && webSocket != null) return

        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        actualSocketConnected = false

        isConnecting = true
        _talkingStatus.postValue("RECONNECTING...")
        _communicationState.postValue(CommState.RECONNECTING)

        val request = Request.Builder().url(SERVER_URL).build()

        if (client == null) {
            client = OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connected ✅")
                isConnecting = false
                reconnectDelay = 2000L
                actualSocketConnected = true
                isAuthenticatedOnCurrentSocket = false
                _connectionStatus.postValue(true)

                cancelDisconnectDebounce()

                if (isAuthorizedSession && !savedUsername.isNullOrEmpty() && !savedPassword.isNullOrEmpty()) {
                    executeLogin(savedUsername!!, savedPassword!!)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (e: Exception) {}
                }
                handleBinaryMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting = false
                actualSocketConnected = false
                handleDisconnectCleanup(immediate = (code == 1000))
                if (code != 1000 && isAuthorizedSession) {
                    attemptReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                isConnecting = false
                actualSocketConnected = false
                handleDisconnectCleanup(immediate = false)
                if (isAuthorizedSession) {
                    attemptReconnect()
                }
            }
        })
    }

    private fun cancelDisconnectDebounce() {
        disconnectDebounceRunnable?.let { pttHandler.removeCallbacks(it) }
        disconnectDebounceRunnable = null
    }

    private fun handleDisconnectCleanup(immediate: Boolean = false) {
        if (immediate) {
            performActualCleanup()
        } else {
            cancelDisconnectDebounce()
            val runnable = Runnable {
                if (!actualSocketConnected) {
                    performActualCleanup()
                }
            }
            disconnectDebounceRunnable = runnable
            pttHandler.postDelayed(runnable, DEBOUNCE_DISCONNECT_MS)
        }
    }

    private fun performActualCleanup() {
        isAuthenticatedOnCurrentSocket = false
        _connectionStatus.postValue(false)
        
        if (isAuthorizedSession) {
            _talkingStatus.postValue("RECONNECTING...")
            _communicationState.postValue(CommState.RECONNECTING)
        } else {
            _talkingStatus.postValue("OFFLINE")
        }
        
        _usersOnline.postValue(JSONArray())

        if (internalPtpTargetId != null) {
            setPtpTarget(null, null)
        }

        if (internalIsTalking) {
            pttHandler.post { stopTalking() }
        }

        resetTalkingState()
    }

    private fun attemptReconnect() {
        if (!isAuthorizedSession) return

        pttHandler.removeCallbacksAndMessages("reconnect_tag")
        val runnable = Runnable {
            if (isAuthorizedSession && (!actualSocketConnected || webSocket == null)) {
                Log.d(TAG, "Attempting automatic reconnect... Delay: $reconnectDelay ms")
                connect()
                // Exponential backoff
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
        // Gunakan postAtTime dengan token untuk management callback yang lebih baik
        pttHandler.postAtTime(runnable, "reconnect_tag", SystemClock.uptimeMillis() + reconnectDelay)
    }

    private fun handleMessage(text: String) {
        mapListener?.onMessage(text)
        try {
            val payload = JSONObject(text)
            val type = payload.optString("type")
            val dataObj = payload.optJSONObject("data") ?: JSONObject()

            when (type) {
                "login_success" -> {
                    isAuthorizedSession = true
                    isAuthenticatedOnCurrentSocket = true
                    reconnectDelay = 2000L // Reset delay sukses login
                    myUserId = dataObj.optString("id")
                    myUserName = dataObj.optString("username")
                    Log.i(TAG, "LOGIN_SUCCESS")

                    cancelDisconnectDebounce()
                    _connectionStatus.postValue(true)

                    _availableChannels.postValue(dataObj.optJSONArray("channels"))

                    _isRxOnly.postValue(dataObj.optBoolean("is_rx_only", false))
                    _isVideoEnabled.postValue(dataObj.optBoolean("enable_ptt_video", false))
                    _isPtpEnabled.postValue(dataObj.optBoolean("enable_p2p", true))
                    _isMapsEnabled.postValue(dataObj.optBoolean("enable_maps", true))
                    _duplexMode.postValue(dataObj.optString("duplex_mode", "HALF DUPLEX"))

                    _loginEvent.postValue(LoginEvent.Success(myUserId!!, myUserName!!))
                    reportLocation(force = true)

                    val channelToJoin = currentChannelSlug ?: dataObj.optString("default_channel_slug")
                    if (!channelToJoin.isNullOrEmpty()) {
                        joinChannel(channelToJoin)
                    } else {
                        updateTalkingStatusUI()
                    }

                    if (internalIsTalking) {
                        executePttStartSignal()
                        executeStartRecording()
                    }
                }

                "login_error" -> {
                    isAuthenticatedOnCurrentSocket = false
                    val msg = dataObj.optString("message", "Login Gagal")
                    _loginEvent.postValue(LoginEvent.Error(msg))
                    // Jika login gagal karena kredensial, mungkin sebaiknya berhenti mencoba
                    // Tapi jika karena server error, attemptReconnect akan tetap jalan dari onClosed/onFailure
                }

                "force_logout" -> {
                    isAuthorizedSession = false
                    _loginEvent.postValue(LoginEvent.ForceLogout)
                    disconnect()
                }

                "user_profile_update" -> {
                    val newName = dataObj.optString("name")
                    if (!newName.isNullOrEmpty()) {
                        myUserName = newName
                    }
                }

                "channels_updated", "channels_update" -> {
                    val channels = dataObj.optJSONArray("channels")
                    if (channels != null) {
                        _availableChannels.postValue(channels)
                        Log.i(TAG, "Realtime sync: ${channels.length()} channels received")

                        var currentStillExists = false
                        for (i in 0 until channels.length()) {
                            val chanObj = channels.optJSONObject(i)
                            if (chanObj?.optString("slug") == currentChannelSlug) {
                                currentStillExists = true

                                val newName = chanObj.optString("name")
                                if (!newName.isNullOrEmpty() && newName != lastChannelName) {
                                    lastChannelName = newName
                                    if (internalPtpTargetName == null) {
                                        _channelName.postValue(lastChannelName)
                                        if (isAuthorizedSession) {
                                            prefs?.edit()?.putString("last_channel_name", lastChannelName)?.apply()
                                        }
                                    }
                                }
                                break
                            }
                        }
                        if (!currentStillExists && currentChannelSlug != null) {
                            Log.w(TAG, "Access to current channel revoked by Admin")
                            if (channels.length() > 0) {
                                joinChannel(channels.optJSONObject(0).optString("slug"))
                            } else {
                                _channelName.postValue("NO CHANNEL")
                                currentChannelSlug = null
                                updateTalkingStatusUI()
                            }
                        }
                    }
                }

                "permission_update" -> {
                    Log.i(TAG, "Permission update received: $dataObj")
                    if (dataObj.has("channels")) {
                        _availableChannels.postValue(dataObj.optJSONArray("channels"))
                    }
                    if (dataObj.has("username")) {
                        myUserName = dataObj.optString("username")
                    }
                    if (dataObj.has("is_rx_only")) {
                        val newRxOnly = dataObj.getBoolean("is_rx_only")
                        _isRxOnly.postValue(newRxOnly)
                        if (newRxOnly && internalIsTalking && internalPtpTargetId == null) {
                            pttHandler.post { stopTalking() }
                        }
                    }
                    if (dataObj.has("enable_ptt_video")) _isVideoEnabled.postValue(dataObj.getBoolean("enable_ptt_video"))
                    if (dataObj.has("enable_p2p")) _isPtpEnabled.postValue(dataObj.getBoolean("enable_p2p"))
                    if (dataObj.has("enable_maps")) _isMapsEnabled.postValue(dataObj.getBoolean("enable_maps"))
                    if (dataObj.has("duplex_mode")) _duplexMode.postValue(dataObj.optString("duplex_mode", "HALF DUPLEX"))
                    updateTalkingStatusUI()
                }

                "join_channel_success" -> {
                    currentChannelSlug = dataObj.optString("channel_slug")
                    _isRxOnly.postValue(dataObj.optBoolean("is_rx_only", false))
                    lastChannelName = dataObj.optString("channel_name", "Unknown Channel")

                    if (internalPtpTargetName == null) {
                        _channelName.postValue(lastChannelName)
                    }

                    if (isAuthorizedSession) {
                        prefs?.edit()
                            ?.putString("last_channel_slug", currentChannelSlug)
                            ?.putString("last_channel_name", lastChannelName)
                            ?.apply()
                    }

                    val speakersArray = dataObj.optJSONArray("speakers")
                    synchronized(activeSpeakers) {
                        activeSpeakers.clear()
                        speakerLastSeen.clear()
                        lastSpeakersBeforeEmpty = emptySet()
                        wasSomeoneElseTalking = false
                        if (speakersArray != null) {
                            for (i in 0 until speakersArray.length()) {
                                val sName = speakersArray.optString(i)
                                if (!sName.equals(myUserName, ignoreCase = true)) {
                                    activeSpeakers.add(sName)
                                    speakerLastSeen[sName] = System.currentTimeMillis()
                                }
                            }
                        }
                        if (activeSpeakers.isNotEmpty()) {
                            startIdleMonitoring()
                        }
                    }
                    updateTalkingStatusUI()
                }

                "ptt_active_status" -> {
                    val isPrivate = dataObj.optBoolean("is_private", false)
                    val incomingChannel = dataObj.optString("channel")
                    val targetName = internalPtpTargetName

                    if (targetName != null && !ptpRequestPending) {
                        if (!isPrivate) return
                    } else if (targetName == null) {
                        if (isPrivate) return
                    }

                    if (isPrivate || incomingChannel == currentChannelSlug) {
                        _isPrivateRx.postValue(isPrivate)
                        val speakersArray = dataObj.optJSONArray("speakers")
                        synchronized(activeSpeakers) {
                            val wasEmpty = activeSpeakers.isEmpty()
                            val currentSpeaker = if (activeSpeakers.isNotEmpty()) activeSpeakers.first() else null

                            activeSpeakers.clear()
                            if (speakersArray != null) {
                                for (i in 0 until speakersArray.length()) {
                                    val sName = speakersArray.optString(i)
                                    if (!sName.equals(myUserName, ignoreCase = true)) {
                                        if (targetName != null && isPrivate) {
                                            if (sName.equals(targetName, ignoreCase = true)) {
                                                activeSpeakers.add(sName)
                                                speakerLastSeen[sName] = System.currentTimeMillis()
                                            }
                                        } else {
                                            activeSpeakers.add(sName)
                                            speakerLastSeen[sName] = System.currentTimeMillis()
                                        }
                                    }
                                }
                            }
                            val isNowTalking = activeSpeakers.isNotEmpty()
                            if (!wasEmpty && !isNowTalking) {
                                currentSpeaker?.let {
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    _lastSpeaker.postValue("Terakhir: $it @ $time")
                                }
                            }

                            if (isNowTalking) startIdleMonitoring()
                        }
                        updateTalkingStatusUI()
                    }
                }

                "ptt_error" -> {
                    val msg = dataObj.optString("message", "Gagal Bicara")
                    appContext?.let {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Batalkan status bicara lokal jika server menolak
                    if (internalIsTalking) {
                        pttHandler.post { stopTalking() }
                    }
                }

                "video_stream_status" -> {
                    val isPrivate = dataObj.optBoolean("is_private", false)
                    val incomingChannel = dataObj.optString("channel")
                    val targetName = internalPtpTargetName
                    val isPtpVid = _isPtpVideo.value == true

                    if (targetName != null && isPtpVid) {
                        if (!isPrivate) return
                    } else if (targetName == null) {
                        if (isPrivate) return
                    } else if (targetName != null && !isPtpVid) {
                        if (!isPrivate) return
                    }

                    if (isPrivate || incomingChannel == currentChannelSlug) {
                        val streamersArray = dataObj.optJSONArray("streamers")
                        val streamers = mutableSetOf<String>()
                        if (streamersArray != null) {
                            for (i in 0 until streamersArray.length()) {
                                val sName = streamersArray.optString(i)
                                if (!sName.equals(myUserName, ignoreCase = true)) {
                                    if (targetName != null && isPrivate) {
                                        if (sName.equals(targetName, ignoreCase = true)) streamers.add(sName)
                                    } else {
                                        streamers.add(sName)
                                    }
                                }
                            }
                        }
                        _activeVideoStreamers.postValue(streamers)
                    }
                }

                "ptp_invitation" -> {
                    if (_isPtpEnabled.value == true) {
                        _isPtpVideo.postValue(false)
                        setPtpTarget(dataObj.optString("sender_id"), dataObj.optString("sender_name"))
                        emit("accept_ptp", JSONObject().put("target_id", dataObj.optString("sender_id")))
                    }
                }

                "ptp_confirmed" -> {
                    _isPtpVideo.postValue(false)
                    setPtpTarget(dataObj.optString("target_id"), dataObj.optString("target_name"))
                    startIdleMonitoring()
                }

                "ptp_video_invitation" -> {
                    if (_isPtpEnabled.value == true && _isVideoEnabled.value == true) {
                        setPtpTarget(dataObj.optString("sender_id"), dataObj.optString("sender_name"))
                        _isPtpVideo.postValue(true)
                        emit("accept_ptp_video", JSONObject().put("target_id", dataObj.optString("sender_id")))
                        _navigateToVideo.postValue(true)
                    }
                }

                "ptp_video_confirmed" -> {
                    setPtpTarget(dataObj.optString("target_id"), dataObj.optString("target_name"))
                    _isPtpVideo.postValue(true)
                    _navigateToVideo.postValue(true)
                    startIdleMonitoring()
                }

                "ptp_cancelled" -> {
                    setPtpTarget(null, null)
                    _isPtpVideo.postValue(false)
                }

                "ptp_failed" -> {
                    pttHandler.removeCallbacks(ptpTimeoutRunnable)
                    ptpRequestPending = false
                    val msg = dataObj.optString("message", "Permintaan Gagal")
                    _ptpHandshakeEvent.postValue(PtpHandshakeEvent.Failed(msg))
                    updateTalkingStatusUI()
                }

                "users_online" -> {
                    val data = payload.opt("data")
                    if (data is JSONArray) {
                        myUserId?.let { id ->
                            for (i in 0 until data.length()) {
                                val user = data.optJSONObject(i)
                                if (user?.optString("id") == id) {
                                    val newName = user.optString("name")
                                    if (!newName.isNullOrEmpty() && newName != myUserName) {
                                        myUserName = newName
                                    }
                                    break
                                }
                            }
                        }

                        _usersOnline.postValue(data)

                        val targetId = internalPtpTargetId
                        if (targetId != null) {
                            var isTargetStillOnline = false
                            for (i in 0 until data.length()) {
                                if (data.optJSONObject(i)?.optString("id") == targetId) {
                                    isTargetStillOnline = true
                                    break
                                }
                            }
                            if (!isTargetStillOnline) {
                                setPtpTarget(null, null)
                            }
                        }
                        updateTalkingStatusUI()
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "HandleMessage Error", e) }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        if (bytes.size() <= 5) return
        try {
            val dataArray = bytes.toByteArray()
            val type = dataArray[0].toInt()

            val buffer = ByteBuffer.wrap(dataArray, 1, 4).order(ByteOrder.LITTLE_ENDIAN)
            val userIdTruncated = buffer.int

            var senderName = findUserNameById(userIdTruncated)
            val targetId = internalPtpTargetId
            val targetIdInt = targetId?.toTruncatedId() ?: -1

            if (senderName == null && targetId != null && userIdTruncated == targetIdInt) {
                senderName = internalPtpTargetName
            }

            if (senderName == null) return

            val payload = ByteArray(dataArray.size - 5)
            System.arraycopy(dataArray, 5, payload, 0, payload.size)

            if (type == 1) {
                synchronized(activeSpeakers) {
                    if (targetId != null && !ptpRequestPending) {
                        if (userIdTruncated != targetIdInt) return
                    }

                    speakerLastSeen[senderName!!] = System.currentTimeMillis()

                    if (!activeSpeakers.contains(senderName) && !senderName.equals(myUserName, ignoreCase = true)) {
                        activeSpeakers.add(senderName)
                        pttHandler.post { updateTalkingStatusUI() }
                        startIdleMonitoring()
                    }
                }
                AudioPlayer.playAudio(senderName!!, payload)
            } else if (type == 2) {
                if (targetId != null && !ptpRequestPending) {
                    if (userIdTruncated != targetIdInt) return
                }
                _incomingVideoFrame.postValue(Pair(senderName!!, payload))
            }
        } catch (e: Exception) { Log.e(TAG, "Binary Error", e) }
    }

    private fun findUserNameById(id: Int): String? {
        val users = _usersOnline.value ?: return null
        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            val userIdStr = user.optString("id")
            if (userIdStr.toTruncatedId() == id) return user.optString("name")
            val usernameStr = user.optString("username")
            if (usernameStr.toTruncatedId() == id) return user.optString("name")
        }
        return null
    }

    private fun startIdleMonitoring() {
        pttHandler.removeCallbacks(idleCheckRunnable)
        pttHandler.post(idleCheckRunnable)
    }

    private fun updateTalkingStatusUI() {
        synchronized(activeSpeakers) {
            val now = System.currentTimeMillis()
            val it = activeSpeakers.iterator()
            while (it.hasNext()) {
                val s = it.next()
                val last = speakerLastSeen[s] ?: 0L
                if (now - last > 2000) {
                    it.remove()
                    speakerLastSeen.remove(s)
                }
            }

            val isAudioPlaying = AudioPlayer.isActuallyPlaying()

            val effectiveSpeakers = if (activeSpeakers.isNotEmpty()) {
                val current = activeSpeakers.toSet()
                lastSpeakersBeforeEmpty = current
                current
            } else if (isAudioPlaying && lastSpeakersBeforeEmpty.isNotEmpty()) {
                lastSpeakersBeforeEmpty
            } else {
                emptySet()
            }

            val isNowTalking = effectiveSpeakers.isNotEmpty()
            wasSomeoneElseTalking = isNowTalking

            val isMeTalking = internalIsTalking
            val isCommActive = isMeTalking || effectiveSpeakers.isNotEmpty()

            if (_isCommunicationActive.value != isCommActive) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    _isCommunicationActive.value = isCommActive
                } else {
                    _isCommunicationActive.postValue(isCommActive)
                }
            }

            val isIdle = !isMeTalking && effectiveSpeakers.isEmpty()
            val ptpName = internalPtpTargetName

            val status = when {
                !actualSocketConnected || !isAuthenticatedOnCurrentSocket -> {
                    if (isAuthorizedSession) "RECONNECTING..." else "OFFLINE"
                }
                ptpRequestPending -> "Memanggil $ptpName..."
                isMeTalking -> "You are Speaking"
                effectiveSpeakers.isNotEmpty() -> {
                    val names = effectiveSpeakers.joinToString(", ")
                    if (effectiveSpeakers.size == 1) "$names is Speaking"
                    else "$names are Speaking"
                }
                else -> "IDLE"
            }

            val displayStatus = status

            if (_talkingStatus.value != displayStatus) {
                _talkingStatus.postValue(displayStatus)
            }

            if (_activeSpeakersList.value != effectiveSpeakers) {
                _activeSpeakersList.postValue(effectiveSpeakers)
            }
        }
    }

    fun login(user: String, pass: String) {
        savedUsername = user.uppercase().trim()
        savedPassword = pass.trim()
        isAuthorizedSession = true
        reconnectDelay = 2000L // Reset delay saat login baru

        prefs?.edit()
            ?.putString("username", savedUsername)
            ?.putString("password", savedPassword)
            ?.apply()

        if (webSocket == null || !actualSocketConnected) {
            connect()
        } else {
            executeLogin(savedUsername!!, savedPassword!!)
        }
    }

    private fun executeLogin(user: String, pass: String) {
        val data = JSONObject()
            .put("username", user)
            .put("password", pass)
            .put("current_device_id", deviceId)
        emit("app_login", data)
    }

    fun clearLoginEvent() {
        _loginEvent.postValue(null)
    }

    fun resetNavigation() {
        _navigateToVideo.postValue(false)
    }

    fun isTalkingNow(): Boolean = internalIsTalking

    fun startTalking() {
        // Blokir jika tidak terhubung ke server
        if (!isConnectedOnSocket() || myUserId == null) return

        val isRx = _isRxOnly.value ?: false
        val ptpId = internalPtpTargetId
        if (isRx && ptpId == null) return

        if (internalIsTalking || ptpRequestPending) return

        val now = System.currentTimeMillis()
        // Cegah spam tombol (debouncing) - minimal 300ms antar PTT
        if (now - lastPttEndTime < 300) return
        lastPttStartTime = now

        // --- VALIDASI LOCAL HALF DUPLEX ---
        val duplex = _duplexMode.value ?: "HALF DUPLEX"
        if (duplex == "HALF DUPLEX" && ptpId == null) {
            val someoneElseTalking = synchronized(activeSpeakers) { activeSpeakers.isNotEmpty() }
            if (someoneElseTalking) {
                appContext?.let {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(it, "Jalur sedang digunakan ", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }

        // --- PENANGANAN GATEWAY MODE ---
        val isGateway = prefs?.getBoolean("gateway_mode", false) ?: false

        // 1. Set status bicara SEGERA (gunakan value= jika di main thread untuk respons instan)
        internalIsTalking = true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isTalking.value = true
        } else {
            _isTalking.postValue(true)
        }

        // 2. Kirim status bicara ke server SEGERA agar "Speaking" muncul di rekan secepat mungkin
        executePttStartSignal()

        // 3. Mainkan nada Start TX (otomatis diabaikan di SoundManager jika gateway_mode=true)
        SoundManager.playStartTx()

        // 4. Jika gateway, pastikan AudioRecorder sudah running dan kirim data audio INSTAN
        if (isGateway) {
            executeStartRecording()
        }

        // --- MUTING OUTPUT IN HALF DUPLEX ---
        if (duplex == "HALF DUPLEX") {
            AudioPlayer.setMute(true)
            SoundManager.setMute(true)
        }

        synchronized(activeSpeakers) {
            wasSomeoneElseTalking = false
        }

        updateTalkingStatusUI()
        reportLocation(force = false)

        // Delay perekaman untuk mode selain gateway (Handheld/BT butuh waktu sinkronisasi hardware)
        if (!isGateway) {
            val delay = if (AudioDeviceManager.isBluetoothConnected) 700L else 400L
            pttHandler.postDelayed({
                executeStartRecording()
            }, delay)
        }
    }

    private fun executePttStartSignal() {
        val target = internalPtpTargetId
        if (!target.isNullOrEmpty()) {
            if (_isPtpEnabled.value == false) return
            emit("ptt_audio_start_private", JSONObject().put("target_id", target))
        } else {
            val slug = currentChannelSlug ?: return
            emit("ptt_audio_start", JSONObject().put("channel_slug", slug))
        }
    }

    private fun executeStartRecording() {
        if (!internalIsTalking) return
        val target = internalPtpTargetId
        if (!target.isNullOrEmpty()) {
            AudioRecorder.startRecording("private_$target")
        } else {
            val slug = currentChannelSlug ?: return
            AudioRecorder.startRecording(slug)
        }
    }

    fun stopTalking() {
        if (!internalIsTalking) return
        
        val now = System.currentTimeMillis()
        lastPttEndTime = now
        
        // Minimal durasi PTT 500ms untuk mencegah paket audio kosong/terputus
        val elapsed = now - lastPttStartTime
        if (elapsed < 500) {
            pttHandler.postDelayed({ stopTalking() }, 500 - elapsed)
            return
        }

        internalIsTalking = false
        _isTalking.postValue(false)
        updateTalkingStatusUI()

        // --- UNMUTING OUTPUT ---
        AudioPlayer.setMute(false)
        SoundManager.setMute(false)

        // Mainkan nada Stop TX
        SoundManager.playStopTx()

        myUserName?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            _lastSpeaker.postValue("Terakhir: $it @ $time")
        }

        AudioRecorder.stopRecording()
        pttHandler.postDelayed({
            if (!internalIsTalking) {
                val target = internalPtpTargetId
                if (!target.isNullOrEmpty()) emit("ptt_audio_end_private", JSONObject().put("target_id", target))
                else currentChannelSlug?.let { emit("ptt_audio_end", JSONObject().put("channel_slug", it)) }
            }
        }, 100)
    }

    fun startVideoStreaming() {
        if (_isVideoEnabled.value == false) return
        val isRx = _isRxOnly.value ?: false
        if (isRx && internalPtpTargetId == null) return

        val target = internalPtpTargetId
        if (!target.isNullOrEmpty()) {
            if (_isPtpEnabled.value == false) return
            emit("ptt_video_start_private", JSONObject().put("target_id", target))
        } else {
            val slug = currentChannelSlug ?: return
            emit("ptt_video_start", JSONObject().put("channel_slug", slug))
        }
    }

    fun stopVideoStreaming() {
        val target = internalPtpTargetId
        if (!target.isNullOrEmpty()) {
            emit("ptt_video_end_private", JSONObject().put("target_id", target))
        } else {
            val slug = currentChannelSlug ?: return
            emit("ptt_video_end", JSONObject().put("channel_slug", slug))
        }
    }

    fun sendVideoFrame(frameData: ByteArray) {
        if (!actualSocketConnected) return
        val userId = myUserId?.toTruncatedId() ?: 0
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        header.put(2.toByte())
        header.putInt(userId)
        val packet = header.array() + frameData
        sendBinary(packet)
    }

    fun sendAudioData(data: ByteArray) {
        if (!actualSocketConnected) return
        val userId = myUserId?.toTruncatedId() ?: 0
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        header.put(1.toByte())
        header.putInt(userId)
        val packet = header.array() + data
        sendBinary(packet)
    }

    fun joinChannel(slug: String) {
        currentChannelSlug = slug
        resetTalkingState()
        emit("join_channel", JSONObject().put("new_channel_slug", slug))
    }

    fun reportLocation(force: Boolean = false) {
        val context = appContext ?: return
        if (!actualSocketConnected) return
        LocationHelper.getLastKnownLocation(context) { lat, lon, accuracy, _ ->
            if (lat != 0.0) updateLocation(lat, lon, accuracy, force)
        }
    }

    fun reportLocationImmediate() {
        reportLocation(force = true)
    }

    fun updateLocation(lat: Double, lon: Double, accuracy: Float, force: Boolean = false) {
        if (!actualSocketConnected) return

        if (!force && lastSentLat != 0.0 && lastSentLon != 0.0) {
            val results = FloatArray(1)
            Location.distanceBetween(lastSentLat, lastSentLon, lat, lon, results)
            if (results[0] < 100f) return
        }

        lastSentLat = lat
        lastSentLon = lon
        emit("update_location", JSONObject().put("latitude", lat).put("longitude", lon).put("accuracy", accuracy))
    }

    fun emit(event: String, data: JSONObject) { webSocket?.send(JSONObject().put("type", event).put("data", data).toString()) }
    fun sendBinary(data: ByteArray) { webSocket?.send(ByteString.of(*data)) }

    fun isConnected(): Boolean = _connectionStatus.value == true

    fun isConnectedOnSocket(): Boolean = actualSocketConnected && isAuthenticatedOnCurrentSocket

    fun disconnect() {
        isAuthorizedSession = false
        isAuthenticatedOnCurrentSocket = false
        webSocket?.close(1000, "Logout")
        webSocket = null
        actualSocketConnected = false
        _connectionStatus.postValue(false)
        clearSession()
    }

    private fun clearSession() {
        myUserId = null
        myUserName = null
        mapListener = null
        cancelDisconnectDebounce()
        pttHandler.removeCallbacksAndMessages("reconnect_tag")
    }
}
