package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.am2.am2.databinding.ActivityLoginBinding
import java.io.File

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var safeContext: Context
    private var interactiveLoginPending = false

    private val REQUIRED_PERMISSIONS: Array<String>
        get() {
            val permissions = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE
            )
            
            // Tambahkan izin storage untuk penyimpanan permanen (khusus Android 9 ke bawah)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            
            return permissions.toTypedArray()
        }

    private val PERMISSION_REQUEST_CODE = 1001
    private val BACKGROUND_LOCATION_PERMISSION_CODE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gunakan Device Protected Storage agar data bisa dibaca saat Boot (sebelum Unlock PIN/Pola)
        safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationContext.createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

        // Inisialisasi WebSocketManager dengan safeContext agar auto-login saat boot lancar
        WebSocketManager.init(safeContext)

        if (WebSocketManager.myUserName != null) {
            startMainActivity()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = safeContext.getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        } else {
            checkBackgroundLocation()
        }

        WebSocketManager.connect()
        setupSocketListeners()

        checkAutoLogin()

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.cbRememberMe.setOnCheckedChangeListener { _, isChecked ->
            updateInputStates(isChecked)
            if (isChecked) {
                binding.btnLogin.requestFocus()
                // Hide keyboard if visible
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.cbRememberMe.windowToken, 0)
            }
        }
    }

    private fun updateInputStates(isLocked: Boolean) {
        val isEnabled = !isLocked
        
        binding.tilUsername.isEnabled = isEnabled
        binding.etUsername.isFocusable = isEnabled
        binding.etUsername.isFocusableInTouchMode = isEnabled
        
        binding.tilPassword.isEnabled = isEnabled
        binding.etPassword.isFocusable = isEnabled
        binding.etPassword.isFocusableInTouchMode = isEnabled

        if (isLocked) {
            binding.tilUsername.alpha = 0.5f
            binding.tilPassword.alpha = 0.5f
        } else {
            binding.tilUsername.alpha = 1.0f
            binding.tilPassword.alpha = 1.0f
        }
    }

    private fun performLogin() {
        val identity = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (identity.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Username & Password wajib diisi", Toast.LENGTH_SHORT).show()
        } else {
            if (allPermissionsGranted()) {
                sendLoginRequest(identity, password)
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
                Toast.makeText(this, "Harap izinkan semua perijinan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackgroundLocation) {
                showBackgroundLocationRationale()
            } else {
                checkBatteryOptimizations()
            }
        } else {
            checkBatteryOptimizations()
        }
    }

    private fun showBackgroundLocationRationale() {
        AlertDialog.Builder(this)
            .setTitle("Izin Lokasi Latar Belakang")
            .setMessage("Aplikasi ini membutuhkan izin lokasi 'Izinkan Sepanjang Waktu' agar fitur pelacakan anggota tetap berfungsi saat aplikasi di latar belakang atau layar mati.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        BACKGROUND_LOCATION_PERMISSION_CODE
                    )
                }
            }
            .setNegativeButton("Nanti") { _, _ -> checkBatteryOptimizations() }
            .setCancelable(false)
            .show()
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Optimasi Baterai")
            .setMessage("Agar layanan PTT dan GPS tidak terputus di latar belakang, harap nonaktifkan optimasi baterai untuk aplikasi ini.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focusedView = currentFocus
                if (focusedView is EditText) {
                    if (focusedView.isEnabled) {
                        showKeyboard(focusedView)
                    }
                    return true
                } else if (focusedView == binding.btnLogin) {
                    performLogin()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showKeyboard(editText: EditText) {
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkBackgroundLocation()
            } else {
                Toast.makeText(this, "Izin dasar diperlukan agar aplikasi dapat berfungsi", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_CODE) {
            checkBatteryOptimizations()
        }
    }

    private fun setupSocketListeners() {
        WebSocketManager.connectionStatus.observe(this) { isConnected ->
            runOnUiThread {
                if (isConnected) {
                    binding.ivServerStatus.setColorFilter(Color.GREEN)
                    binding.tvConnectionStatus.text = "Server Online"
                    binding.tvConnectionStatus.setTextColor(Color.GREEN)
                } else {
                    binding.ivServerStatus.setColorFilter(Color.RED)
                    binding.tvConnectionStatus.text = "Server Offline"
                    binding.tvConnectionStatus.setTextColor(Color.RED)
                }
            }
        }

        WebSocketManager.loginEvent.observe(this) { event ->
            when (event) {
                is WebSocketManager.LoginEvent.Success -> {
                    runOnUiThread {
                        // WebSocketManager owns the persisted session. This
                        // event also fires for automatic token reconnects, when
                        // there was no interactive request to evaluate.
                        if (interactiveLoginPending && !binding.cbRememberMe.isChecked) {
                            clearCredentials()
                        } else if (interactiveLoginPending) {
                            sharedPreferences.edit().putBoolean("remember_me", true).apply()
                        }
                        interactiveLoginPending = false
                        startMainActivity()
                        WebSocketManager.clearLoginEvent()
                    }
                }
                is WebSocketManager.LoginEvent.Error -> {
                    runOnUiThread {
                        interactiveLoginPending = false
                        Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
                        WebSocketManager.clearLoginEvent()
                    }
                }
                is WebSocketManager.LoginEvent.ForceLogout -> {
                    WebSocketManager.clearLoginEvent()
                }
                null -> {}
            }
        }
    }

    private fun saveCredentials(user: String, pass: String) {
        // Sealed where the platform can seal it; see CredentialStore. The
        // second copy this used to write -- cred.txt, cleartext, described as a
        // backup -- doubled the exposure and protected nothing.
        CredentialStore.save(safeContext, user, pass)
        sharedPreferences.edit().putBoolean("remember_me", true).apply()
    }

    private fun clearCredentials() {
        CredentialStore.clear(safeContext)
        sharedPreferences.edit().putBoolean("remember_me", false).apply()
    }

    private fun sendLoginRequest(identity: String, pass: String) {
        interactiveLoginPending = true
        WebSocketManager.login(identity, pass)
    }

    private fun checkAutoLogin() {
        // One reader, which also migrates a handset that still has its
        // credentials in cleartext and removes them once it has.
        val stored = CredentialStore.state(safeContext)
        val savedUser = stored.username
        val savedPass = stored.password
        val rememberMe = sharedPreferences.getBoolean("remember_me", false)

        if (savedUser != null) {
            binding.etUsername.setText(savedUser)
            binding.cbRememberMe.isChecked = rememberMe
            if (rememberMe) {
                updateInputStates(true)
                binding.btnLogin.requestFocus()
            }
        }
        if (savedPass != null) binding.etPassword.setText(savedPass)
    }
}
