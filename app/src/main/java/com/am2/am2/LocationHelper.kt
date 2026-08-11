package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

object LocationHelper {

    private const val TAG = "LOC_HELPER"
    private const val LOCATION_TIMEOUT = 10000L
    private const val RECENT_LOCATION_THRESHOLD = 30000L

    private const val PREF_LOC_CACHE = "LOCATION_CACHE"
    private const val LAST_LAT = "LAST_KNOWN_LAT"
    private const val LAST_LON = "LAST_KNOWN_LON"
    private const val LAST_ADDR = "LAST_KNOWN_ADDR"
    private const val PERSISTENT_ADDR_CACHE = "ADDRESS_PERSISTENT_CACHE"

    private val geocoderExecutor = Executors.newFixedThreadPool(5)
    private val mainHandler = Handler(Looper.getMainLooper())

    /*
     * Keep this global client because MainActivity still references:
     * LocationHelper.okHttpClient
     *
     * This client has NO trust-all, NO custom HostnameVerifier.
     */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /*
     * Internal client for LocationHelper calls.
     *
     * This keeps Android lama support via TlsCompat, but does NOT trust all certs.
     * TlsCompat must only add bundled CA and keep hostname/certificate validation.
     */
    private fun createOkHttpClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        return TlsCompat.applyBundledCaForOldAndroid(
            context.applicationContext,
            builder
        ).build()
    }

    private val addressCache = LruCache<String, String>(300)
    private val isCacheLoaded = AtomicBoolean(false)

    private fun ensureCacheLoaded(context: Context) {
        if (isCacheLoaded.compareAndSet(false, true)) {
            try {
                val cachePrefs = context.getSharedPreferences(
                    PERSISTENT_ADDR_CACHE,
                    Context.MODE_PRIVATE
                )

                cachePrefs.all.forEach { (key, value) ->
                    if (value is String) {
                        addressCache.put(key, value)
                    }
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Cache load failed", e)
            }
        }
    }

    fun isLikelyAddress(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.any { it.isLetter() }
    }

    fun saveLastLocation(
        context: Context,
        lat: Double,
        lon: Double,
        address: String? = null
    ) {
        if (lat == 0.0 || lon == 0.0) return

        ensureCacheLoaded(context)

        if (isLikelyAddress(address)) {
            val key = String.format(Locale.US, "%.4f,%.4f", lat, lon)
            val safeAddress = address!!

            addressCache.put(key, safeAddress)

            context.getSharedPreferences(PERSISTENT_ADDR_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString(key, safeAddress)
                .apply()
        }

        context.getSharedPreferences(PREF_LOC_CACHE, Context.MODE_PRIVATE)
            .edit()
            .apply {
                putFloat(LAST_LAT, lat.toFloat())
                putFloat(LAST_LON, lon.toFloat())

                if (isLikelyAddress(address)) {
                    putString(LAST_ADDR, address)
                }

                apply()
            }
    }

    fun getCachedLocation(context: Context): GeoPoint {
        val prefs = context.getSharedPreferences(PREF_LOC_CACHE, Context.MODE_PRIVATE)

        val lat = prefs.getFloat(LAST_LAT, -6.2000f).toDouble()
        val lon = prefs.getFloat(LAST_LON, 106.8166f).toDouble()

        return GeoPoint(lat, lon)
    }

    fun getAddressFast(context: Context, lat: Double, lon: Double): String? {
        if (lat == 0.0 || lon == 0.0) return null

        ensureCacheLoaded(context)

        val key = String.format(Locale.US, "%.4f,%.4f", lat, lon)

        addressCache.get(key)?.let {
            return it
        }

        synchronized(addressCache) {
            val snapshot = addressCache.snapshot()

            for ((coords, addr) in snapshot) {
                val parts = coords.split(",")

                if (parts.size == 2) {
                    val cLat = parts[0].toDoubleOrNull() ?: 0.0
                    val cLon = parts[1].toDoubleOrNull() ?: 0.0

                    if (
                        abs(lat - cLat) < 0.0008 &&
                        abs(lon - cLon) < 0.0008
                    ) {
                        return addr
                    }
                }
            }
        }

        return null
    }

    fun getAddressLocally(context: Context, lat: Double, lon: Double): String {
        return getAddressFast(context, lat, lon)
            ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    @SuppressLint("MissingPermission")
    fun startLiveLocationUpdates(
        context: Context,
        callback: (lat: Double, lon: Double, accuracy: Float, address: String?) -> Unit
    ): Any? {
        val gmsStatus = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)

        if (gmsStatus == ConnectionResult.SUCCESS) {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        processLocationResult(context, loc, callback)
                    }
                }
            }

            val request = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
                interval = 15000L
                fastestInterval = 10000L
                smallestDisplacement = 10f
            }

            fusedClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )

            return locationCallback
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: Location) {
                processLocationResult(context, loc, callback)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

        mainHandler.post {
            try {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.NETWORK_PROVIDER
                }

                lm.requestLocationUpdates(
                    provider,
                    15000L,
                    10f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                SafeLog.e(TAG, "Native live failed", e)
            }
        }

        return listener
    }

    @SuppressLint("MissingPermission")
    fun stopLiveLocationUpdates(context: Context, listener: Any?) {
        if (listener == null) return

        if (listener is LocationCallback) {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(listener)
        } else if (listener is android.location.LocationListener) {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.removeUpdates(listener)
        }
    }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(
        context: Context,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        val gmsStatus = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)

        if (gmsStatus == ConnectionResult.SUCCESS) {
            useGmsLocation(context, callback)
        } else {
            useNativeLocation(context, callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun useGmsLocation(
        context: Context,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        fusedClient.lastLocation
            .addOnSuccessListener { loc ->
                if (
                    loc != null &&
                    System.currentTimeMillis() - loc.time < RECENT_LOCATION_THRESHOLD
                ) {
                    processLocationResult(context, loc, callback)
                } else {
                    requestFreshGmsLocation(context, callback)
                }
            }
            .addOnFailureListener {
                useNativeLocation(context, callback)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshGmsLocation(
        context: Context,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val delivered = AtomicBoolean(false)

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (delivered.compareAndSet(false, true)) {
                        fusedClient.removeLocationUpdates(this)
                        processLocationResult(context, loc, callback)
                    }
                }
            }
        }

        val request = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
            interval = 2000L
        }

        fusedClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        mainHandler.postDelayed({
            if (delivered.compareAndSet(false, true)) {
                fusedClient.removeLocationUpdates(locationCallback)
                useNativeLocation(context, callback)
            }
        }, LOCATION_TIMEOUT)
    }

    @SuppressLint("MissingPermission")
    private fun useNativeLocation(
        context: Context,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        mainHandler.post {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@post

            val delivered = AtomicBoolean(false)

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (delivered.compareAndSet(false, true)) {
                        lm.removeUpdates(this)
                        processLocationResult(context, loc, callback)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}
            }

            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Native failed", e)
            }

            mainHandler.postDelayed({
                if (delivered.compareAndSet(false, true)) {
                    lm.removeUpdates(listener)
                    useIpLocation(context, callback)
                }
            }, LOCATION_TIMEOUT)
        }
    }

    /*
     * Keep ip-api HTTP fallback as requested.
     *
     * Important:
     * - Do not send token/password/session here.
     * - Allow cleartext only for ip-api.com in network_security_config.xml.
     */
    private fun useIpLocation(
        context: Context,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        SafeLog.d(TAG, "Attempting IP Geolocation fallback...")

        geocoderExecutor.execute {
            try {
                val client = createOkHttpClient(context)

                val response = client.newCall(
                    Request.Builder()
                        .url("http://ip-api.com/json")
                        .header("User-Agent", "AM2-App")
                        .build()
                ).execute()

                val body = response.body()?.string()

                if (body != null) {
                    val json = JSONObject(body)

                    if (json.optString("status") == "success") {
                        val lat = json.getDouble("lat")
                        val lon = json.getDouble("lon")
                        val city = json.optString("city", "Unknown")
                        val isp = json.optString("isp", "Unknown")

                        mainHandler.post {
                            SafeLog.d(TAG, "IP Location found: $lat, $lon ($city)")
                            callback(lat, lon, 2000f, "Lokasi IP: $city ($isp)")
                        }

                        return@execute
                    }
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "IP Geolocation failed", e)
            }

            mainHandler.post {
                val cached = getCachedLocation(context)
                callback(cached.latitude, cached.longitude, 0f, null)
            }
        }
    }

    private fun processLocationResult(
        context: Context,
        loc: Location,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        val fastAddr = getAddressFast(context, loc.latitude, loc.longitude)

        callback(
            loc.latitude,
            loc.longitude,
            loc.accuracy,
            fastAddr
        )

        performGeocode(
            context,
            loc.latitude,
            loc.longitude,
            loc.accuracy,
            callback
        )
    }

    fun performGeocode(
        context: Context,
        lat: Double,
        lon: Double,
        acc: Float = 0f,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        if (lat == 0.0 || lon == 0.0) return

        val cached = getAddressFast(context, lat, lon)

        if (cached != null) {
            callback(lat, lon, acc, cached)
            return
        }

        geocoderExecutor.execute {
            var found = false

            try {
                val geocoder = Geocoder(context, Locale("id", "ID"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        if (addresses.isNotEmpty()) {
                            val detailAddr = formatDetailedAddress(addresses[0])
                            saveLastLocation(context, lat, lon, detailAddr)

                            mainHandler.post {
                                callback(lat, lon, acc, detailAddr)
                            }
                        }
                    }

                    Thread.sleep(800)
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)

                    if (!addresses.isNullOrEmpty()) {
                        val detailAddr = formatDetailedAddress(addresses[0])
                        saveLastLocation(context, lat, lon, detailAddr)

                        mainHandler.post {
                            callback(lat, lon, acc, detailAddr)
                        }

                        found = true
                    }
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "Geocoder failed, trying OSM...", e)
            }

            if (!found) {
                val cachedSecond = getAddressFast(context, lat, lon)

                if (cachedSecond != null) {
                    mainHandler.post {
                        callback(lat, lon, acc, cachedSecond)
                    }

                    found = true
                }
            }

            if (!found) {
                performOsmGeocode(context, lat, lon, acc, callback)
            }
        }
    }

    private fun performOsmGeocode(
        context: Context,
        lat: Double,
        lon: Double,
        acc: Float,
        callback: (Double, Double, Float, String?) -> Unit
    ) {
        try {
            val url = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1",
                lat,
                lon
            )

            val client = createOkHttpClient(context)

            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "TIK-App")
                    .build()
            ).execute()

            val json = JSONObject(response.body()?.string() ?: "")
            val display = json.optString("display_name", null)

            if (!display.isNullOrEmpty()) {
                saveLastLocation(context, lat, lon, display)

                mainHandler.post {
                    callback(lat, lon, acc, display)
                }
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "OSM Geocode failed", e)
        }
    }

    private fun formatDetailedAddress(addr: android.location.Address): String {
        val sb = StringBuilder()
        val mainLine = addr.getAddressLine(0)

        if (mainLine != null) {
            sb.append(mainLine)
        } else {
            addr.featureName?.let {
                sb.append(it).append(", ")
            }

            addr.subLocality?.let {
                sb.append(it).append(", ")
            }

            addr.locality?.let {
                sb.append(it)
            }
        }

        val district = addr.subAdminArea

        if (
            mainLine != null &&
            district != null &&
            !mainLine.contains(district, true)
        ) {
            sb.append(", ").append(district)
        }

        return sb.toString()
            .trim()
            .removeSuffix(",")
    }
}