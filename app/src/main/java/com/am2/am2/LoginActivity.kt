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

    /*
     * This screen needs the relay reachable before anybody has signed in, and
     * reconnecting used to be gated on having a session. The first drop -- the
     * phone sleeping is enough -- left it reporting "Server Offline" against a
     * relay that was up, until a login called connect() directly.
     */
    override fun onStart() {
        super.onStart()
        WebSocketManager.wantTransport(true)
    }

    override fun onStop() {
        super.onStop()
        // Released, not disconnected. The operator may have just signed in
        // through this screen, and that session is not ours to end.
        WebSocketManager.wantTransport(false)
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
                        startMainActivity()
                        WebSocketManager.clearLoginEvent()
                    }
                }
                is WebSocketManager.LoginEvent.Error -> {
                    runOnUiThread {
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

    private fun sendLoginRequest(identity: String, pass: String) {
        /*
         * No choice is offered any more. A radio assigned to a unit stays
         * signed in until somebody signs it out or an admin revokes it, which
         * is what every purpose-built field device does. The control that used
         * to sit here did more than decline to save: an unticked sign-in ran
         * CredentialStore.clear() and threw away a token that was working.
         */
        sharedPreferences.edit().putString(LAST_USERNAME, identity).apply()
        WebSocketManager.login(identity, pass)
    }

    companion object {
        /** Not a credential: the unit id, kept so it need not be retyped. */
        const val LAST_USERNAME = "last_username"
    }

    private fun checkAutoLogin() {
        // One reader, which also migrates a handset that still has its
        // credentials in cleartext and removes them once it has.
        val stored = CredentialStore.state(safeContext)

        /*
         * The unit id, from wherever it still exists.
         *
         * It outlives signing out on purpose. It is not a credential -- it is
         * printed on the radio -- and retyping it was the part that was
         * actually tedious. The password is a different thing entirely and is
         * never kept, never filled in, and never survives a sign-out.
         */
        val savedUser = stored.username
            ?: sharedPreferences.getString(LAST_USERNAME, null)
        if (!savedUser.isNullOrEmpty()) {
            binding.etUsername.setText(savedUser)
            binding.etPassword.requestFocus()
        }
        /*
         * The password is not filled in. The relay issues a device token and
         * CredentialStore deletes the password the moment one arrives, so
         * there is normally nothing to fill -- and on a handset that has not
         * migrated yet this was the last place that would put one back on a
         * visible field.
         */
    }
}
