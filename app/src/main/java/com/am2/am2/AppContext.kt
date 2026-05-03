package com.am2.am2

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.multidex.MultiDexApplication
import org.osmdroid.config.Configuration
import java.io.File

class AppContext : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // OPTIMASI OSMDROID: Konfigurasi global agar selalu menggunakan Internal Storage
        // Dilakukan di AppContext agar terpanggil sebelum MapView di-init di activity mana pun.
        val osmConfig = Configuration.getInstance()
        val internalCache = File(filesDir, "osmdroid")
        if (!internalCache.exists()) internalCache.mkdirs()
        
        osmConfig.osmdroidBasePath = internalCache
        osmConfig.osmdroidTileCache = File(internalCache, "tiles")
        osmConfig.userAgentValue = packageName
        
        // Load existing configuration from default shared preferences if needed,
        // but force paths to internal storage again after load.
        val osmPrefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        osmConfig.load(this, osmPrefs)
        osmConfig.osmdroidBasePath = internalCache
        osmConfig.osmdroidTileCache = File(internalCache, "tiles")
        osmConfig.save(this, osmPrefs)

        NetworkManager.initialize(this)
        
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { AppStatus.onActivityStarted() }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) { AppStatus.onActivityStopped() }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    companion object {
        lateinit var instance: AppContext
            private set
    }
}
