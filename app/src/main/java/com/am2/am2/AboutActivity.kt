package com.am2.am2

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.am2.am2.databinding.ActivityAboutBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val VERSION_JSON_URL = "https://apiapi.am2-poc.com/update/version.json"

    private val okClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        builder.build()
        TlsCompat.applyBundledCaForOldAndroid(this, builder).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "1.0.0" }
        binding.tvAppVersion.text = "Versi $currentVersionName"

        binding.tvAboutUserId.text = "Username: ${WebSocketManager.myUserId ?: "-"}"
        
        WebSocketManager.myUserNameLiveData.observe(this) { name ->
            binding.tvAboutUserName.text = "Nama: ${name ?: "-"}"
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        checkDownloadedUpdate()
    }

    private fun checkDownloadedUpdate() {
        thread {
            try {
                val currentVersionName = try {
                    packageManager.getPackageInfo(packageName, 0).versionName?.trim() ?: "1.0.0"
                } catch (e: Exception) { "1.0.0" }

                val downloadDir = getExternalFilesDir(null)
                val files = downloadDir?.listFiles { file ->
                    file.name.startsWith("update_") && file.name.endsWith(".apk")
                }

                files?.forEach { file ->
                    val fileVersion = file.name.substringAfter("update_").substringBefore(".apk")
                    // Hapus jika versi file sama atau lebih lama dari versi yang terpasang
                    if (!isNewerVersion(fileVersion, currentVersionName) || !isValidApk(file)) {
                        file.delete()
                    }
                }

                val remainingFiles = downloadDir?.listFiles { file ->
                    file.name.startsWith("update_") && file.name.endsWith(".apk")
                }
                val latestFile = remainingFiles?.maxByOrNull { it.lastModified() }

                if (latestFile != null) {
                    val fileVersion = latestFile.name.substringAfter("update_").substringBefore(".apk")
                    runOnUiThread {
                        binding.tvLatestVersion.text = "Update $fileVersion siap pasang"
                        binding.tvLatestVersion.visibility = View.VISIBLE
                        binding.tvLatestVersion.setOnClickListener {
                            showInstallDialog(latestFile, fileVersion)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AboutActivity", "Error checking downloaded update", e)
            }
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.tvLatestVersion.text = "Memeriksa pembaruan..."
        binding.tvLatestVersion.visibility = View.VISIBLE

        thread {
            try {
                val request = Request.Builder()
                    .url(VERSION_JSON_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                okClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Gagal baca server: ${response.code()}")

                    val responseText = response.body()?.string() ?: throw Exception("Respon kosong")
                    val json = JSONObject(responseText)

                    val serverVersionName = json.getString("version_name").trim()
                    val serverVersionCode = json.optLong("version_code", 0L)
                    val downloadUrl = json.getString("download_url")
                    val changelog = json.optString("changelog", "Perbaikan bug dan stabilitas.")

                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                    }
                    val currentVersionName = pInfo.versionName?.trim() ?: "1.0.0"

                    runOnUiThread {
                        binding.btnCheckUpdate.isEnabled = true

                        // LOGIKA PENGECEKAN: Apakah versi server benar-benar lebih baru?
                        val isUpdateAvailable = if (serverVersionCode > 0) {
                            serverVersionCode > currentVersionCode
                        } else {
                            isNewerVersion(serverVersionName, currentVersionName)
                        }

                        if (isUpdateAvailable) {
                            binding.tvLatestVersion.text = "Versi baru tersedia: $serverVersionName"
                            binding.tvLatestVersion.visibility = View.VISIBLE

                            val destination = File(getExternalFilesDir(null), "update_$serverVersionName.apk")
                            if (isValidApk(destination)) {
                                binding.tvLatestVersion.text = "Update $serverVersionName siap pasang"
                                showInstallDialog(destination, serverVersionName)
                            } else {
                                showUpdateDialog(serverVersionName, changelog, downloadUrl)
                            }
                        } else {
                            // JIKA VERSI SAMA ATAU LEBIH TINGGI (Aplikasi sudah terbaru)
                            binding.tvLatestVersion.text = "Versi saat ini v$currentVersionName sudah terbaru"
                            binding.tvLatestVersion.visibility = View.VISIBLE
                            binding.tvLatestVersion.setOnClickListener(null)

                            AlertDialog.Builder(this@AboutActivity)
                                .setTitle("Informasi")
                                .setMessage("Aplikasi Anda (v$currentVersionName) sudah menggunakan versi terbaru.")
                                .setPositiveButton("OK", null)
                                .show()

                            // Bersihkan file APK lama jika ada
                            thread {
                                try {
                                    getExternalFilesDir(null)?.listFiles { f ->
                                        f.name.startsWith("update_") && f.name.endsWith(".apk")
                                    }?.forEach { it.delete() }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvLatestVersion.text = "Gagal memeriksa: ${e.message}"
                }
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val lStr = latest.trim().trimStart('v', 'V')
        val cStr = current.trim().trimStart('v', 'V')
        if (lStr == cStr) return false

        return try {
            val latestParts = lStr.split(".").map { it.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = cStr.split(".").map { it.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = if (i < latestParts.size) latestParts[i] else 0
                val c = if (i < currentParts.size) currentParts[i] else 0
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            lStr > cStr
        }
    }

    private fun showUpdateDialog(versionName: String, changelog: String, downloadUrl: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pembaruan Tersedia")
            .setMessage("Versi Baru: $versionName\n\nCatatan:\n$changelog\n\nUnduh sekarang?")
            .setPositiveButton("UNDUH") { _, _ ->
                startManualDownload(downloadUrl, versionName)
            }
            .setNegativeButton("NANTI", null)
            .create()

        dialog.show()
        val width = resources.getDimensionPixelSize(R.dimen.overlay_dialog_width)
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showInstallDialog(file: File, version: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pasang Pembaruan")
            .setMessage("Update v$version sudah diunduh. Pasang sekarang?")
            .setPositiveButton("PASANG") { _, _ ->
                installApk(file)
            }
            .setNegativeButton("NANTI", null)
            .create()

        dialog.show()
        val width = resources.getDimensionPixelSize(R.dimen.overlay_dialog_width)
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun startManualDownload(downloadUrl: String, version: String) {
        val destination = File(getExternalFilesDir(null), "update_$version.apk")
        if (isValidApk(destination)) {
            runOnUiThread { installApk(destination) }
            return
        }

        Toast.makeText(this, "Mengunduh pembaruan...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                if (destination.exists()) destination.delete()

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                okClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Gagal unduh APK (${response.code()})")

                    val body = response.body() ?: throw Exception("File kosong")
                    FileOutputStream(destination).use { output ->
                        body.byteStream().copyTo(output)
                    }

                    if (isValidApk(destination)) {
                        runOnUiThread { installApk(destination) }
                    } else {
                        destination.delete()
                        throw Exception("File APK tidak valid.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 100 * 1024) return false
        return try {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            }
            info != null
        } catch (e: Exception) { false }
    }

    private fun installApk(file: File) {
        try {
            if (!file.exists()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                }
            }

            val intent = Intent(Intent.ACTION_VIEW)
            val mimeType = "application/vnd.android.package-archive"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkUri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                intent.setDataAndType(apkUri, mimeType)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                @Suppress("DEPRECATION")
                intent.setDataAndType(Uri.parse("file://${file.absolutePath}"), mimeType)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menginstal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}