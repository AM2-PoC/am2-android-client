package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

object WebSocketManager {
    private const val TAG = "WebSocketManager"
    private val SERVER_URL = BuildConfig.WEBSOCKET_URL
    private const val PREFS_NAME = "AM2_PREFS"

    private const val DEBOUNCE_DISCONNECT_MS = 5000L
    private const val MAX_RECONNECT_DELAY = 10000L
    private const val AUTHORIZATION_FALLBACK_MS = 500L
    private const val BLUETOOTH_ROUTE_FALLBACK_MS = 700L

    private val RECONNECT_TOKEN = Any()

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var prefs: SharedPreferences? = null
    private var deviceId: String? = null
    private var appContext: Context? = null

    private var lastSentLat: Double = 0.0
    private var lastSentLon: Double = 0.0

    @Volatile private var isConnecting = false
    @Volatile private var reconnectDelay = 2000L
    @Volatile private var actualSocketConnected = false
    @Volatile private var socketGeneration = 0
    @Volatile private var reconnectAttempts = 0
    @Volatile private var lastDisconnect = "none"

    private var disconnectDebounceRunnable: Runnable? = null

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
    private val receivePttTraces = PttReceiveTraceRegistry()
    /* Video refused so audio would not wait. Reported, never silently absorbed. */
    private val videoFramesDropped = java.util.concurrent.atomic.AtomicLong(0)
    private var lastSpeakersBeforeEmpty = emptySet<String>()
    private var wasSomeoneElseTalking = false
    @Volatile private var activeTransmitTraceId: Long? = null
    @Volatile private var transmitFrameSequence = 0L
    @Volatile private var captureStarted = false
    @Volatile private var transmitAuthorized = false
    private var pendingAuthTimeout: Runnable? = null

    private val _activeVideoStreamers = MutableLiveData<Set<String>>(emptySet())
    val activeVideoStreamers: LiveData<Set<String>> = _activeVideoStreamers

    private val _incomingVideoFrame = MutableLiveData<Pair<String, ByteArray>>()
    val incomingVideoFrame: LiveData<Pair<String, ByteArray>> = _incomingVideoFrame

    enum class CommState {
        OFFLINE,
        RECONNECTING,
        IDLE,
        TX,
        RX,
        CALLING
    }

    private val _communicationState = MutableLiveData<CommState>(CommState.OFFLINE)
    val communicationState: LiveData<CommState> = _communicationState

    private val _talkingStatus = MutableLiveData("OFFLINE")
    val talkingStatus: LiveData<String> = _talkingStatus

    private val _channelName = MutableLiveData("Connecting...")
    val channelName: LiveData<String> = _channelName
    private var lastChannelName: String = "Connecting..."

    private val _lastSpeaker = MutableLiveData("Terakhir: -")
    val lastSpeaker: LiveData<String> = _lastSpeaker

    private val _connectionStatus = MutableLiveData(false)
    val connectionStatus: LiveData<Boolean> = _connectionStatus

    private val _isTalking = MutableLiveData(false)
    val isTalking: LiveData<Boolean> = _isTalking

    private val _isCommunicationActive = MutableLiveData(false)
    val isCommunicationActive: LiveData<Boolean> = _isCommunicationActive

    private val _isRxOnly = MutableLiveData(false)
    val isRxOnly: LiveData<Boolean> = _isRxOnly

    private val _isVideoEnabled = MutableLiveData(true)
    val isVideoEnabled: LiveData<Boolean> = _isVideoEnabled

    private val _isPtpEnabled = MutableLiveData(true)
    val isPtpEnabled: LiveData<Boolean> = _isPtpEnabled

    private val _isMapsEnabled = MutableLiveData(true)
    val isMapsEnabled: LiveData<Boolean> = _isMapsEnabled

    private val _duplexMode = MutableLiveData("HALF DUPLEX")
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

    private val _isPrivateRx = MutableLiveData(false)
    val isPrivateRx: LiveData<Boolean> = _isPrivateRx

    private val _isPtpVideo = MutableLiveData(false)
    val isPtpVideo: LiveData<Boolean> = _isPtpVideo

    private val _navigateToVideo = MutableLiveData(false)
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

    @Volatile private var internalIsTalking = false
    private var mapListener: OnMessageListener? = null

    private val pttHandler = Handler(Looper.getMainLooper())
    private var lastPttStartTime: Long = 0
    private var lastPttEndTime: Long = 0

    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            updateTalkingStatusUI()
            if (activeSpeakers.isEmpty() && !AudioPlayer.isActuallyPlaying()) {
                return
            }
            pttHandler.postDelayed(this, 200)
        }
    }

    private val ptpTimeoutRunnable = Runnable {
        if (ptpRequestPending) {
            ptpRequestPending = false
            _ptpHandshakeEvent.postValue(
                PtpHandshakeEvent.Failed("Permintaan waktu habis. Personel tidak merespon.")
            )
            updateTalkingStatusUI()
        }
    }

    interface OnMessageListener {
        fun onMessage(text: String?)
    }

    private fun createWebSocketClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        return TlsCompat.applyPlatformTlsCompatibility(
            context.applicationContext,
            builder
        ).build()
    }

    fun init(context: Context) {
        if (prefs != null && client != null) return

        appContext = context.applicationContext
        client = createWebSocketClient(context.applicationContext)

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

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
            } catch (_: Exception) {
            }
        }

        currentChannelSlug = prefs?.getString("last_channel_slug", null)

        val startOnBoot = prefs?.getBoolean("start_on_boot", false) ?: false
        isAuthorizedSession = startOnBoot &&
                !savedUsername.isNullOrEmpty() &&
                !savedPassword.isNullOrEmpty()

        val lastSavedName = prefs?.getString(
            "last_channel_name",
            "Connecting..."
        ) ?: "Connecting..."

        lastChannelName = lastSavedName
        _channelName.postValue(lastChannelName)
    }

    private fun String.toTruncatedId(): Int {
        return this.toLongOrNull()?.toInt() ?: this.hashCode()
    }

    fun setMapListener(listener: OnMessageListener?) {
        mapListener = listener
    }

    private fun resetTalkingState() {
        emitReceiveTraceTransitions(receivePttTraces.clear())
        synchronized(activeSpeakers) {
            activeSpeakers.clear()
            speakerLastSeen.clear()
            lastSpeakersBeforeEmpty = emptySet()
            wasSomeoneElseTalking = false
        }
        AudioPlayer.stop()
    }

    private fun emitReceiveTraceTransitions(transitions: List<PttReceiveTraceTransition>) {
        transitions.forEach { transition ->
            PttTrace.emit(
                event = when (transition) {
                    is PttReceiveTraceTransition.Started -> "receive_started"
                    is PttReceiveTraceTransition.Ended -> "receive_ended"
                },
                traceId = transition.traceId,
            )
        }
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

    private fun isCurrentSocket(generation: Int): Boolean {
        return generation == socketGeneration
    }

    private fun cancelReconnect() {
        pttHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
    }

    @Synchronized
    fun connect() {
        if (isConnecting) return
        if (actualSocketConnected && webSocket != null) return

        val context = appContext
        if (context == null) {
            isConnecting = false
            _talkingStatus.postValue("OFFLINE")
            _communicationState.postValue(CommState.OFFLINE)
            SafeLog.e(TAG, "WebSocketManager.init(context) must be called before connect()")
            return
        }

        if (client == null) {
            client = createWebSocketClient(context)
        }

        /*
         * Increment generation BEFORE closing old socket.
         * This invalidates all late callbacks from old sockets.
         */
        val generation = socketGeneration + 1
        socketGeneration = generation

        val oldSocket = webSocket
        webSocket = null
        actualSocketConnected = false
        isAuthenticatedOnCurrentSocket = false

        try {
            oldSocket?.close(1000, "Reconnecting")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to close old socket cleanly", e)
        }

        isConnecting = true
        _talkingStatus.postValue("RECONNECTING...")
        _communicationState.postValue(CommState.RECONNECTING)

        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        SafeLog.i(TAG, "Opening WebSocket generation=$generation url=$SERVER_URL")

        val newSocket = client?.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale onOpen generation=$generation current=$socketGeneration"
                        )
                        try {
                            socket.close(1000, "Stale socket")
                        } catch (_: Exception) {
                        }
                        return
                    }

                    SafeLog.i(TAG, "WebSocket Connected ✅ generation=$generation")

                    webSocket = socket
                    isConnecting = false
                    reconnectDelay = 2000L
                    actualSocketConnected = true
                    isAuthenticatedOnCurrentSocket = false
                    reconnectAttempts = 0

                    _connectionStatus.postValue(true)

                    cancelDisconnectDebounce()
                    cancelReconnect()

                    if (
                        isAuthorizedSession &&
                        !savedUsername.isNullOrEmpty() &&
                        !savedPassword.isNullOrEmpty()
                    ) {
                        executeLogin(savedUsername!!, savedPassword!!)
                    } else {
                        updateTalkingStatusUI()
                    }
                }

                override fun onMessage(socket: WebSocket, text: String) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale text message generation=$generation current=$socketGeneration"
                        )
                        return
                    }

                    handleMessage(text)
                }

                override fun onMessage(socket: WebSocket, bytes: ByteString) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale binary message generation=$generation current=$socketGeneration"
                        )
                        return
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                            )
                        } catch (_: Exception) {
                        }
                    }

                    handleBinaryMessage(bytes)
                }

                override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale onClosing code=$code reason=$reason generation=$generation current=$socketGeneration"
                        )
                        return
                    }

                    SafeLog.w(TAG, "WebSocket closing code=$code reason=$reason generation=$generation")
                }

                override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale onClosed code=$code reason=$reason generation=$generation current=$socketGeneration"
                        )
                        return
                    }

                    SafeLog.w(TAG, "WebSocket closed code=$code reason=$reason generation=$generation")
                    lastDisconnect = "closed code=$code reason=${reason.ifBlank { "none" }}"

                    isConnecting = false
                    actualSocketConnected = false
                    isAuthenticatedOnCurrentSocket = false

                    if (webSocket === socket) {
                        webSocket = null
                    }

                    handleDisconnectCleanup(immediate = (code == 1000))

                    if (ReconnectPolicy.shouldReconnect(isAuthorizedSession, code)) {
                        attemptReconnect()
                    }
                }

                override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentSocket(generation)) {
                        SafeLog.d(
                            TAG,
                            "Ignoring stale onFailure message=${t.message} generation=$generation current=$socketGeneration"
                        )
                        return
                    }

                    SafeLog.e(
                        TAG,
                        "WebSocket failure generation=$generation message=${t.message}",
                        t
                    )
                    lastDisconnect = "failure ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"

                    isConnecting = false
                    actualSocketConnected = false
                    isAuthenticatedOnCurrentSocket = false

                    if (webSocket === socket) {
                        webSocket = null
                    }

                    handleDisconnectCleanup(immediate = false)

                    if (isAuthorizedSession) {
                        attemptReconnect()
                    }
                }
            }
        )

        webSocket = newSocket
    }

    private fun cancelDisconnectDebounce() {
        disconnectDebounceRunnable?.let {
            pttHandler.removeCallbacks(it)
        }
        disconnectDebounceRunnable = null
    }

    private fun handleDisconnectCleanup(immediate: Boolean = false) {
        if (immediate) {
            performActualCleanup()
            return
        }

        cancelDisconnectDebounce()

        val runnable = Runnable {
            if (!actualSocketConnected) {
                performActualCleanup()
            }
        }

        disconnectDebounceRunnable = runnable
        pttHandler.postDelayed(runnable, DEBOUNCE_DISCONNECT_MS)
    }

    private fun performActualCleanup() {
        isAuthenticatedOnCurrentSocket = false
        _connectionStatus.postValue(false)

        if (isAuthorizedSession) {
            _talkingStatus.postValue("RECONNECTING...")
            _communicationState.postValue(CommState.RECONNECTING)
        } else {
            _talkingStatus.postValue("OFFLINE")
            _communicationState.postValue(CommState.OFFLINE)
        }

        _usersOnline.postValue(JSONArray())

        if (internalPtpTargetId != null) {
            setPtpTarget(null, null)
        }

        if (internalIsTalking) {
            pttHandler.post {
                stopTalking()
            }
        }

        resetTalkingState()
    }

    private fun attemptReconnect() {
        if (!isAuthorizedSession) return

        cancelReconnect()

        val delay = reconnectDelay

        val runnable = Runnable {
            if (
                isAuthorizedSession &&
                (!actualSocketConnected || webSocket == null)
            ) {
                SafeLog.d(TAG, "Attempting automatic reconnect. Delay was $delay ms")
                reconnectAttempts += 1
                connect()
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }

        pttHandler.postAtTime(
            runnable,
            RECONNECT_TOKEN,
            SystemClock.uptimeMillis() + delay
        )
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
                    reconnectDelay = 2000L

                    myUserId = dataObj.optString("id")
                    myUserName = dataObj.optString("username")

                    SafeLog.i(TAG, "LOGIN_SUCCESS user=[REDACTED] id=[REDACTED]")

                    cancelDisconnectDebounce()
                    cancelReconnect()

                    _connectionStatus.postValue(true)

                    _availableChannels.postValue(dataObj.optJSONArray("channels"))

                    _isRxOnly.postValue(dataObj.optBoolean("is_rx_only", false))
                    _isVideoEnabled.postValue(dataObj.optBoolean("enable_ptt_video", false))
                    _isPtpEnabled.postValue(dataObj.optBoolean("enable_p2p", true))
                    _isMapsEnabled.postValue(dataObj.optBoolean("enable_maps", true))
                    _duplexMode.postValue(dataObj.optString("duplex_mode", "HALF DUPLEX"))

                    _loginEvent.postValue(LoginEvent.Success(myUserId!!, myUserName!!))

                    reportLocation(force = true)

                    val channelToJoin = currentChannelSlug
                        ?: dataObj.optString("default_channel_slug")

                    if (!channelToJoin.isNullOrEmpty()) {
                        joinChannel(channelToJoin)
                    } else {
                        updateTalkingStatusUI()
                    }

                    if (internalIsTalking) {
                        // A reconnect must re-request relay authorization; the
                        // acknowledgement from before the drop no longer stands.
                        // Non-gateway capture waits for the new one under the same
                        // fallback bound as the initial press.
                        transmitAuthorized = false
                        executePttStartSignal()
                        if (prefs?.getBoolean("gateway_mode", false) == true) {
                            executeStartRecording()
                        } else if (!captureStarted) {
                            armAuthorizationFallback()
                        }
                    }
                }

                "login_error" -> {
                    val hadAuthenticatedSession = isAuthenticatedOnCurrentSocket
                    isAuthorizedSession = AuthRetryPolicy.keepAuthorizedSession(
                        hadAuthenticatedSession
                    )
                    isAuthenticatedOnCurrentSocket = false
                    if (!isAuthorizedSession) {
                        cancelReconnect()
                    }
                    val msg = dataObj.optString("message", "Login Gagal")
                    _loginEvent.postValue(LoginEvent.Error(msg))
                    updateTalkingStatusUI()
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

                "channels_updated",
                "channels_update" -> {
                    val channels = dataObj.optJSONArray("channels")
                    if (channels != null) {
                        _availableChannels.postValue(channels)
                        SafeLog.i(TAG, "Realtime sync: ${channels.length()} channels received")

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
                                            prefs?.edit()
                                                ?.putString("last_channel_name", lastChannelName)
                                                ?.apply()
                                        }
                                    }
                                }

                                break
                            }
                        }

                        if (!currentStillExists && currentChannelSlug != null) {
                            SafeLog.w(TAG, "Access to current channel revoked by Admin")

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
                    SafeLog.i(TAG, "Permission update received: $dataObj")

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
                            pttHandler.post {
                                stopTalking()
                            }
                        }
                    }

                    if (dataObj.has("enable_ptt_video")) {
                        _isVideoEnabled.postValue(dataObj.getBoolean("enable_ptt_video"))
                    }

                    if (dataObj.has("enable_p2p")) {
                        _isPtpEnabled.postValue(dataObj.getBoolean("enable_p2p"))
                    }

                    if (dataObj.has("enable_maps")) {
                        _isMapsEnabled.postValue(dataObj.getBoolean("enable_maps"))
                    }

                    if (dataObj.has("duplex_mode")) {
                        _duplexMode.postValue(
                            dataObj.optString("duplex_mode", "HALF DUPLEX")
                        )
                    }

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

                "ptt_audio_start_authorized" -> {
                    val traceId = dataObj.optLong("trace_id", 0L)
                    if (traceId > 0L && traceId == activeTransmitTraceId && internalIsTalking) {
                        PttTrace.emit(event = "start_authorized", traceId = traceId)
                        transmitAuthorized = true
                        startCaptureWhenReady()
                    }
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
                            val currentSpeaker = if (activeSpeakers.isNotEmpty()) {
                                activeSpeakers.first()
                            } else {
                                null
                            }

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
                                    val time = SimpleDateFormat(
                                        "HH:mm",
                                        Locale.getDefault()
                                    ).format(Date())

                                    _lastSpeaker.postValue("Terakhir: $it @ $time")
                                }
                            }

                            if (isNowTalking) {
                                startIdleMonitoring()
                            }
                        }

                        val relayedTraceId = dataObj.optLong("trace_id", 0L).takeIf { it > 0L }
                        val relayedSender = if (speakersArray?.length() == 1) {
                            speakersArray.optString(0).takeIf { it.isNotEmpty() }
                        } else null
                        emitReceiveTraceTransitions(
                            receivePttTraces.syncActive(
                                activeSpeakers.toSet(),
                                relayedSender,
                                relayedTraceId,
                            ),
                        )
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

                    if (internalIsTalking) {
                        pttHandler.post {
                            stopTalking()
                        }
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
                                        if (sName.equals(targetName, ignoreCase = true)) {
                                            streamers.add(sName)
                                        }
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
                        setPtpTarget(
                            dataObj.optString("sender_id"),
                            dataObj.optString("sender_name")
                        )
                        emit(
                            "accept_ptp",
                            JSONObject().put("target_id", dataObj.optString("sender_id"))
                        )
                    }
                }

                "ptp_confirmed" -> {
                    _isPtpVideo.postValue(false)
                    setPtpTarget(
                        dataObj.optString("target_id"),
                        dataObj.optString("target_name")
                    )
                    startIdleMonitoring()
                }

                "ptp_video_invitation" -> {
                    if (_isPtpEnabled.value == true && _isVideoEnabled.value == true) {
                        setPtpTarget(
                            dataObj.optString("sender_id"),
                            dataObj.optString("sender_name")
                        )
                        _isPtpVideo.postValue(true)
                        emit(
                            "accept_ptp_video",
                            JSONObject().put("target_id", dataObj.optString("sender_id"))
                        )
                        _navigateToVideo.postValue(true)
                    }
                }

                "ptp_video_confirmed" -> {
                    setPtpTarget(
                        dataObj.optString("target_id"),
                        dataObj.optString("target_name")
                    )
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
        } catch (e: Exception) {
            SafeLog.e(TAG, "HandleMessage Error", e)
        }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        if (bytes.size() <= 5) return

        try {
            val dataArray = bytes.toByteArray()
            val type = dataArray[0].toInt()

            val buffer = ByteBuffer
                .wrap(dataArray, 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)

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

                    speakerLastSeen[senderName] = System.currentTimeMillis()

                    if (
                        !activeSpeakers.contains(senderName) &&
                        !senderName.equals(myUserName, ignoreCase = true)
                    ) {
                        activeSpeakers.add(senderName)
                        pttHandler.post {
                            updateTalkingStatusUI()
                        }
                        startIdleMonitoring()
                    }
                }

                val receiveTraceId = receivePttTraces.traceIdForFrame(senderName)
                AudioPlayer.playAudio(senderName, payload, receiveTraceId)
            } else if (type == 2) {
                if (targetId != null && !ptpRequestPending) {
                    if (userIdTruncated != targetIdInt) return
                }

                _incomingVideoFrame.postValue(Pair(senderName, payload))
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Binary Error", e)
        }
    }

    private fun findUserNameById(id: Int): String? {
        val users = _usersOnline.value ?: return null

        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue

            val userIdStr = user.optString("id")
            if (userIdStr.toTruncatedId() == id) {
                return user.optString("name")
            }

            val usernameStr = user.optString("username")
            if (usernameStr.toTruncatedId() == id) {
                return user.optString("name")
            }
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
            val iterator = activeSpeakers.iterator()

            while (iterator.hasNext()) {
                val speaker = iterator.next()
                val last = speakerLastSeen[speaker] ?: 0L

                if (now - last > 2000) {
                    iterator.remove()
                    speakerLastSeen.remove(speaker)
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

            val ptpName = internalPtpTargetName

            val status = when {
                !actualSocketConnected || !isAuthenticatedOnCurrentSocket -> {
                    if (isAuthorizedSession) "RECONNECTING..." else "OFFLINE"
                }

                ptpRequestPending -> {
                    "Memanggil $ptpName..."
                }

                isMeTalking -> {
                    "You are Speaking"
                }

                effectiveSpeakers.isNotEmpty() -> {
                    val names = effectiveSpeakers.joinToString(", ")
                    if (effectiveSpeakers.size == 1) {
                        "$names is Speaking"
                    } else {
                        "$names are Speaking"
                    }
                }

                else -> {
                    "IDLE"
                }
            }

            val commState = when {
                !actualSocketConnected || !isAuthenticatedOnCurrentSocket -> {
                    if (isAuthorizedSession) CommState.RECONNECTING else CommState.OFFLINE
                }

                ptpRequestPending -> {
                    CommState.CALLING
                }

                isMeTalking -> {
                    CommState.TX
                }

                effectiveSpeakers.isNotEmpty() -> {
                    CommState.RX
                }

                else -> {
                    CommState.IDLE
                }
            }

            if (_talkingStatus.value != status) {
                _talkingStatus.postValue(status)
            }

            if (_communicationState.value != commState) {
                _communicationState.postValue(commState)
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
        reconnectDelay = 2000L

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

    fun isTalkingNow(): Boolean {
        return internalIsTalking
    }

    fun currentTransmitTraceId(): Long? = activeTransmitTraceId

    fun startTalking() {
        if (!isConnectedOnSocket() || myUserId == null) return

        val isRx = _isRxOnly.value ?: false
        val ptpId = internalPtpTargetId

        if (isRx && ptpId == null) return
        if (internalIsTalking || ptpRequestPending) return

        val now = System.currentTimeMillis()
        if (now - lastPttEndTime < 300) return

        lastPttStartTime = now

        val duplex = _duplexMode.value ?: "HALF DUPLEX"
        if (duplex == "HALF DUPLEX" && ptpId == null) {
            val someoneElseTalking = synchronized(activeSpeakers) {
                activeSpeakers.isNotEmpty()
            }

            if (someoneElseTalking) {
                appContext?.let {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(it, "Jalur sedang digunakan ", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }

        val isGateway = prefs?.getBoolean("gateway_mode", false) ?: false

        internalIsTalking = true
        captureStarted = false
        transmitAuthorized = false
        activeTransmitTraceId = PttTrace.newTraceId().also {
            transmitFrameSequence = 0L
            PttTrace.emit(event = "button_down", traceId = it)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isTalking.value = true
        } else {
            _isTalking.postValue(true)
        }

        executePttStartSignal()

        SoundManager.playStartTx()

        if (isGateway) {
            executeStartRecording()
        }

        if (duplex == "HALF DUPLEX") {
            AudioPlayer.setMute(true)
            SoundManager.setMute(true)
        }

        synchronized(activeSpeakers) {
            wasSomeoneElseTalking = false
        }

        updateTalkingStatusUI()
        reportLocation(force = false)

        if (!isGateway) {
            armAuthorizationFallback()
        }
    }

    /*
     * Non-gateway capture needs two things, and the former fixed 400/700 ms
     * delay was a single guess covering both: the relay must have authorized
     * the transmission, and the microphone route must be carrying audio.
     *
     * The route matters because AudioRecord binds its input on construction and
     * will not move onto a Bluetooth SCO link that connects afterwards. Opening
     * capture early does not clip the start of a transmission — it pins the
     * whole transmission to the built-in microphone.
     */
    private fun startCaptureWhenReady() {
        if (!internalIsTalking || captureStarted) return
        if (!transmitAuthorized) return
        if (!AudioDeviceManager.isCaptureRouteReady()) return
        executeStartRecording()
    }

    /* Called when a Bluetooth route finishes connecting, which is usually after
     * the press. A transmission already waiting on it starts here. */
    fun onCaptureRouteReady() {
        pttHandler.post { startCaptureWhenReady() }
    }

    /*
     * Neither signal is guaranteed to arrive, so capture still opens on a bound.
     * Without Bluetooth the only wait is the acknowledgement round trip. With a
     * Bluetooth route that has not reported ready, the bound stays at the 700 ms
     * the fixed delay used to spend, so the worst case is no worse than before
     * while the common case pays nothing.
     */
    private fun armAuthorizationFallback() {
        cancelAuthorizationFallback()
        val traceId = activeTransmitTraceId ?: return
        val bound = if (AudioDeviceManager.isBluetoothConnected && !AudioDeviceManager.isScoConnected) {
            BLUETOOTH_ROUTE_FALLBACK_MS
        } else {
            AUTHORIZATION_FALLBACK_MS
        }
        val fallback = Runnable {
            if (internalIsTalking && !captureStarted && traceId == activeTransmitTraceId) {
                PttTrace.emit(event = "start_authorization_timeout", traceId = traceId)
                executeStartRecording()
            }
        }
        pendingAuthTimeout = fallback
        pttHandler.postDelayed(fallback, bound)
    }

    private fun cancelAuthorizationFallback() {
        pendingAuthTimeout?.let { pttHandler.removeCallbacks(it) }
        pendingAuthTimeout = null
    }

    private fun executePttStartSignal() {
        val target = internalPtpTargetId
        val traceId = activeTransmitTraceId ?: return

        if (!target.isNullOrEmpty()) {
            if (_isPtpEnabled.value == false) return
            emit(
                "ptt_audio_start_private",
                JSONObject().put("target_id", target).put("trace_id", traceId),
            )
        } else {
            val slug = currentChannelSlug ?: return
            emit(
                "ptt_audio_start",
                JSONObject().put("channel_slug", slug).put("trace_id", traceId),
            )
        }
        PttTrace.emit(event = "start_sent", traceId = traceId)
    }

    private fun executeStartRecording() {
        if (!internalIsTalking || captureStarted) return
        val target = internalPtpTargetId
        // Claim the capture only once the source is known, so a transmission
        // with no channel yet can still start when one arrives.
        val source = if (!target.isNullOrEmpty()) "private_$target" else currentChannelSlug ?: return

        cancelAuthorizationFallback()
        captureStarted = true
        AudioRecorder.startRecording(source)
    }

    fun stopTalking() {
        if (!internalIsTalking) return

        val now = System.currentTimeMillis()
        lastPttEndTime = now

        val elapsed = now - lastPttStartTime
        if (elapsed < 500) {
            pttHandler.postDelayed({
                stopTalking()
            }, 500 - elapsed)
            return
        }

        internalIsTalking = false
        captureStarted = false
        transmitAuthorized = false
        cancelAuthorizationFallback()
        activeTransmitTraceId?.let { PttTrace.emit(event = "button_up", traceId = it) }
        _isTalking.postValue(false)

        updateTalkingStatusUI()

        AudioPlayer.setMute(false)
        SoundManager.setMute(false)

        SoundManager.playStopTx()

        myUserName?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            _lastSpeaker.postValue("Terakhir: $it @ $time")
        }

        AudioRecorder.stopRecording()

        pttHandler.postDelayed({
            if (!internalIsTalking) {
                val target = internalPtpTargetId

                if (!target.isNullOrEmpty()) {
                    emit(
                        "ptt_audio_end_private",
                        JSONObject().put("target_id", target).put("trace_id", activeTransmitTraceId),
                    )
                } else {
                    currentChannelSlug?.let {
                        emit(
                            "ptt_audio_end",
                            JSONObject().put("channel_slug", it).put("trace_id", activeTransmitTraceId),
                        )
                    }
                }
                activeTransmitTraceId?.let { traceId ->
                    PttTrace.emit(event = "end_sent", traceId = traceId)
                }
                activeTransmitTraceId = null
                transmitFrameSequence = 0L
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

    /**
     * How hard the uplink is pushing back right now.
     *
     * Exposed so the capture side can spend less before it is refused outright:
     * a weak link should soften the picture rather than switch it on and off.
     */
    internal fun videoPressure(): WireAdmission.Pressure =
        WireAdmission.videoPressure(webSocket?.queueSize() ?: 0L)

    /** Video frames refused so far to keep audio ahead of them. */
    fun droppedVideoFrames(): Long = videoFramesDropped.get()

    fun sendVideoFrame(frameData: ByteArray) {
        if (!actualSocketConnected) return

        val userId = myUserId?.toTruncatedId() ?: 0

        val header = ByteBuffer
            .allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put(2.toByte())
        header.putInt(userId)

        val packet = header.array() + frameData
        sendBinary(packet)
    }

    fun sendAudioData(data: ByteArray) {
        if (!actualSocketConnected) return

        val userId = myUserId?.toTruncatedId() ?: 0

        val header = ByteBuffer
            .allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)

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
            if (lat != 0.0) {
                updateLocation(lat, lon, accuracy, force)
            }
        }
    }

    fun reportLocationImmediate() {
        reportLocation(force = true)
    }

    fun updateLocation(
        lat: Double,
        lon: Double,
        accuracy: Float,
        force: Boolean = false
    ) {
        if (!actualSocketConnected) return

        if (!force && lastSentLat != 0.0 && lastSentLon != 0.0) {
            val results = FloatArray(1)

            Location.distanceBetween(
                lastSentLat,
                lastSentLon,
                lat,
                lon,
                results
            )

            if (results[0] < 100f) return
        }

        lastSentLat = lat
        lastSentLon = lon

        emit(
            "update_location",
            JSONObject()
                .put("latitude", lat)
                .put("longitude", lon)
                .put("accuracy", accuracy)
        )
    }

    fun emit(event: String, data: JSONObject) {
        if (!actualSocketConnected) return

        val payload = JSONObject()
            .put("type", event)
            .put("data", data)
            .toString()

        val sent = webSocket?.send(payload) ?: false

        if (!sent) {
            SafeLog.w(TAG, "Failed to send event=$event because WebSocket queue is closed/full")
        }
    }

    fun sendBinary(data: ByteArray) {
        if (!actualSocketConnected) return

        val socket = webSocket
        /*
         * What is already waiting to go out. send() only enqueues and returns
         * immediately, so a true result says the frame was accepted, not that
         * it reached the wire. Recording the backlog here is what separates a
         * frame delayed by the uplink from one delayed by encoding.
         */
        val queueBytes = socket?.queueSize() ?: 0L
        val isAudio = data.firstOrNull()?.toInt() == 1

        /*
         * The one place the two media compete for the wire. Audio is always
         * admitted; video is refused while the socket still holds a frame's
         * worth, so it can never queue ahead of speech.
         *
         * Refused here rather than after enqueueing, because OkHttp cannot be
         * asked to reorder what it already holds. A late video frame has no
         * value either — by the time a backlog drained, its picture would be
         * history — so dropping is the honest outcome, and it is counted.
         */
        if (!isAudio && !WireAdmission.shouldAdmitVideo(queueBytes)) {
            videoFramesDropped.incrementAndGet()
            return
        }

        val sent = socket?.send(ByteString.of(*data)) ?: false

        if (!sent) {
            SafeLog.w(TAG, "Failed to send binary payload size=${data.size}")
            if (isAudio) {
                activeTransmitTraceId?.let { traceId ->
                    PttTrace.emit(
                        event = "frame_dropped",
                        traceId = traceId,
                        frameSequence = transmitFrameSequence,
                        frameBytes = data.size,
                        queueBytes = queueBytes,
                    )
                }
            }
        } else if (isAudio) {
            activeTransmitTraceId?.let { traceId ->
                val frameSequence = ++transmitFrameSequence
                if (PttTrace.shouldSampleFrame(frameSequence)) {
                    PttTrace.emit(
                        event = "frame_sent",
                        traceId = traceId,
                        frameSequence = frameSequence,
                        frameBytes = data.size,
                        queueBytes = queueBytes,
                    )
                }
            }
        }
    }

    fun isConnected(): Boolean {
        return _connectionStatus.value == true
    }

    fun isConnectedOnSocket(): Boolean {
        return actualSocketConnected && isAuthenticatedOnCurrentSocket
    }

    fun diagnostics(appVersion: String, network: String): String {
        return DeviceDiagnostics.format(
            appVersion = appVersion,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            network = network,
            socketConnected = actualSocketConnected,
            socketAuthenticated = isAuthenticatedOnCurrentSocket,
            communicationState = _communicationState.value?.name ?: CommState.OFFLINE.name,
            reconnectAttempts = reconnectAttempts,
            lastDisconnect = lastDisconnect,
        )
    }

    @Synchronized
    fun disconnect() {
        isAuthorizedSession = false
        isAuthenticatedOnCurrentSocket = false
        actualSocketConnected = false
        isConnecting = false

        /*
         * Invalidate callbacks from current socket.
         */
        socketGeneration += 1

        cancelReconnect()
        cancelDisconnectDebounce()

        try {
            webSocket?.close(1000, "Logout")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to close WebSocket on logout", e)
        }

        webSocket = null

        _connectionStatus.postValue(false)
        _talkingStatus.postValue("OFFLINE")
        _communicationState.postValue(CommState.OFFLINE)

        clearSession()
    }

    private fun clearSession() {
        myUserId = null
        myUserName = null
        mapListener = null

        cancelDisconnectDebounce()
        cancelReconnect()

        if (internalPtpTargetId != null) {
            setPtpTarget(null, null)
        }

        if (internalIsTalking) {
            pttHandler.post {
                stopTalking()
            }
        }

        resetTalkingState()
    }
}