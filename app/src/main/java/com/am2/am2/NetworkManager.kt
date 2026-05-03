package com.am2.am2

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.lang.ref.WeakReference

object NetworkManager {
    private const val TAG = "NetworkManager"
    private const val DEBOUNCE_OFFLINE_MS = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private var offlineDebounceRunnable: Runnable? = null

    private val _networkInfo = MutableLiveData<String>("Mencari Jaringan...")
    val networkInfo: LiveData<String> = _networkInfo

    private val _networkIcon = MutableLiveData<Int>(R.drawable.ic_no_network)
    val networkIcon: LiveData<Int> = _networkIcon

    private val _networkColor = MutableLiveData<Int>(Color.WHITE)
    val networkColor: LiveData<Int> = _networkColor

    private var connectivityManager: ConnectivityManager? = null
    private var contextRef: WeakReference<Context>? = null

    private var networkCallback: Any? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        contextRef = WeakReference(appContext)
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setupNetworkCallback()
        }
        updateStatusFromManager(immediate = true)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setupNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Jaringan Tersedia Kembali")
                cancelOfflineDebounce()
                updateStatusFromManager(immediate = true)
                
                // Pemicu Reconnect yang lebih cerdas:
                // Hanya panggil connect() jika WebSocketManager benar-benar terputus total.
                // Jika sedang 'RECONNECTING' di internal WebSocketManager, biarkan internal loop-nya bekerja.
                if (!WebSocketManager.isConnected()) {
                    WebSocketManager.connect()
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Jaringan Terputus")
                // Gunakan delay saat kehilangan jaringan agar UI tidak berkedip
                updateStatusFromManager(immediate = false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                processCapabilities(networkCapabilities)
            }
        }
        networkCallback = callback
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        try {
            connectivityManager?.registerNetworkCallback(networkRequest, callback)
        } catch (e: Exception) {}
    }

    fun updateStatusFromManager(immediate: Boolean = true) {
        val cm = connectivityManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            if (activeNetwork == null) {
                setOfflineStatus(immediate)
                return
            }
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            processCapabilities(capabilities)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            if (info != null && info.isConnected) {
                cancelOfflineDebounce()
                if (info.type == ConnectivityManager.TYPE_WIFI) {
                    val ssid = getWifiSSIDFromManager()
                    _networkInfo.postValue(if (ssid.isEmpty()) "WIFI" else ssid)
                    _networkIcon.postValue(R.drawable.ic_wifi)
                } else {
                    _networkInfo.postValue(getOperatorName())
                    _networkIcon.postValue(R.drawable.ic_cellular)
                }
                _networkColor.postValue(Color.GREEN)
            } else {
                setOfflineStatus(immediate)
            }
        }
    }

    private fun setOfflineStatus(immediate: Boolean = false) {
        if (immediate) {
            cancelOfflineDebounce()
            performSetOffline()
            return
        }

        if (offlineDebounceRunnable != null) return // Sudah dalam proses debounce

        val runnable = Runnable {
            performSetOffline()
            offlineDebounceRunnable = null
        }
        offlineDebounceRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_OFFLINE_MS)
    }

    private fun performSetOffline() {
        _networkInfo.postValue("OFFLINE")
        _networkIcon.postValue(R.drawable.ic_no_network)
        _networkColor.postValue(Color.RED)
    }

    private fun cancelOfflineDebounce() {
        offlineDebounceRunnable?.let { handler.removeCallbacks(it) }
        offlineDebounceRunnable = null
    }

    private fun processCapabilities(capabilities: Any?) {
        if (capabilities == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && capabilities !is NetworkCapabilities)) {
            setOfflineStatus(immediate = false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && capabilities is NetworkCapabilities) {
            cancelOfflineDebounce()
            val speed = capabilities.linkDownstreamBandwidthKbps
            val qualityColor = when {
                speed > 15000 -> Color.GREEN
                speed > 5000 -> Color.YELLOW
                else -> Color.RED
            }
            _networkColor.postValue(qualityColor)

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    var ssid = ""
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val transportInfo = capabilities.transportInfo
                        if (transportInfo is WifiInfo) {
                            ssid = transportInfo.ssid?.replace("\"", "") ?: ""
                        }
                    }
                    if (ssid.isEmpty() || ssid == "<unknown ssid>") {
                        ssid = getWifiSSIDFromManager()
                    }
                    _networkInfo.postValue(if (ssid.isEmpty()) "WIFI" else ssid)
                    _networkIcon.postValue(R.drawable.ic_wifi)
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    _networkInfo.postValue(getOperatorName())
                    _networkIcon.postValue(R.drawable.ic_cellular)
                }
                else -> {
                    _networkInfo.postValue("CONNECTED")
                    _networkIcon.postValue(R.drawable.ic_cellular)
                }
            }
        }
    }

    private fun getWifiSSIDFromManager(): String {
        return try {
            val context = contextRef?.get() ?: return ""
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ssid = info.ssid?.replace("\"", "") ?: ""
            if (ssid == "<unknown ssid>") "" else ssid
        } catch (e: Exception) { "" }
    }

    private fun getOperatorName(): String {
        return try {
            val context = contextRef?.get() ?: return "CELLULAR"
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.networkOperatorName
            if (name.isNullOrEmpty()) "CELLULAR" else name
        } catch (e: Exception) { "CELLULAR" }
    }
}
