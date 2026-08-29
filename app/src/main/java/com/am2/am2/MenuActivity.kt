package com.am2.am2

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import com.am2.am2.databinding.ActivityMenuBinding

class MenuActivity : BaseActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissions()

        // Memberikan fokus awal ke item pertama yang terlihat
        binding.menuGroup.post {
            binding.menuGroup.requestFocus()
        }

        binding.menuGroup.setOnClickListener {
            val intent = Intent(this, GroupActivity::class.java)
            startActivity(intent)
        }

        binding.menuUser.setOnClickListener {
            val intent = Intent(this, UserActivity::class.java)
            startActivity(intent)
        }

        binding.menuVideo.setOnClickListener {
            val intent = Intent(this, VideoActivity::class.java)
            startActivity(intent)
        }

        binding.menuMap.setOnClickListener {
            val intent = Intent(this, MapsActivity::class.java)
            startActivity(intent)
        }

        binding.menuSettings.setOnClickListener {
            val intent = Intent(this, SettingActivity::class.java)
            startActivity(intent)
        }

        binding.menuAbout.setOnClickListener {
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }

        binding.menuLogout.setOnClickListener {
            performLogout()
        }
    }

    private fun setupPermissions() {
        WebSocketManager.isVideoEnabled.observe(this) { enabled ->
            val visibility = if (enabled) View.VISIBLE else View.GONE
            binding.menuVideo.visibility = visibility
            binding.dividerVideo.visibility = visibility
        }

        WebSocketManager.isMapsEnabled.observe(this) { enabled ->
            val visibility = if (enabled) View.VISIBLE else View.GONE
            binding.menuMap.visibility = visibility
            binding.dividerMap.visibility = visibility
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focusedView = currentFocus
            focusedView?.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun performLogout() {
        WebSocketManager.logout()
        val serviceIntent = Intent(this, PTTService::class.java)
        stopService(serviceIntent)
        finishAffinity()
        System.exit(0)
    }
}
