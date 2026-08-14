package com.am2.am2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.am2.am2.databinding.ActivityAboutBinding
import com.am2.am2.update.UpdateMetadata
import com.am2.am2.update.UpdateVerifier
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val VERSION_JSON_URL = BuildConfig.UPDATE_MANIFEST_URL

    private val okClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)

        TlsCompat.applyPlatformTlsCompatibility(this, builder).build()
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
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                checkForUpdates()
            } else {
                Toast.makeText(this, "Pembaruan mandiri dinonaktifkan untuk build ini", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCheckUpdate.isEnabled = BuildConfig.SELF_UPDATE_ENABLED

        binding.btnCopyDiagnostics.setOnClickListener {
            val network = NetworkManager.networkInfo.value ?: "UNKNOWN"
            val diagnostics = WebSocketManager.diagnostics(currentVersionName ?: "unknown", network)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AM2 diagnostics", diagnostics))
            Toast.makeText(this, "Diagnostik disalin", Toast.LENGTH_SHORT).show()
        }

        checkDownloadedUpdate()
    }

    private fun checkDownloadedUpdate() {
        // Artifact bytes are only trusted within the metadata flow that
        // downloaded them. Clear leftovers after a restart rather than infer
        // identity from a filename.
        updateDirectory().listFiles { file ->
            file.name.startsWith("update_") && file.name.endsWith(".apk")
        }?.forEach { it.delete() }
    }

    private fun checkForUpdates() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
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
                    val metadata = UpdateMetadata.parse(responseText)

                    val serverVersionName = metadata.versionName
                    val serverVersionCode = metadata.versionCode

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
                        // Strict metadata always carries a positive numeric version.
                        val isUpdateAvailable = serverVersionCode > currentVersionCode

                        if (isUpdateAvailable) {
                            binding.tvLatestVersion.text = "Versi baru tersedia: $serverVersionName"
                            binding.tvLatestVersion.visibility = View.VISIBLE

                            val destination = File(updateDirectory(), "update_${metadata.versionCode}.apk")
                            if (UpdateVerifier.verify(destination, metadata, currentVersionCode, packageManager)) {
                                binding.tvLatestVersion.text = "Update $serverVersionName siap pasang"
                                showVerifiedInstallDialog(destination, metadata, currentVersionCode)
                            } else {
                                showUpdateDialog(metadata)
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

    private fun showUpdateDialog(metadata: UpdateMetadata) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pembaruan Tersedia")
            .setMessage("Versi Baru: ${metadata.versionName}\n\nCatatan:\n${metadata.changelog}\n\nUnduh sekarang?")
            .setPositiveButton("UNDUH") { _, _ ->
                startManualDownload(metadata)
            }
            .setNegativeButton("NANTI", null)
            .create()

        dialog.show()
        val width = resources.getDimensionPixelSize(R.dimen.overlay_dialog_width)
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showVerifiedInstallDialog(file: File, metadata: UpdateMetadata, installedVersionCode: Long) {
        AlertDialog.Builder(this)
            .setTitle("Pasang Pembaruan")
            .setMessage("Update v${metadata.versionName} sudah diverifikasi. Pasang sekarang?")
            .setPositiveButton("PASANG") { _, _ ->
                if (UpdateVerifier.verify(file, metadata, installedVersionCode, packageManager)) {
                    installApk(file)
                } else {
                    Toast.makeText(this, "APK berubah atau tidak valid", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("NANTI", null)
            .show()
    }

    private fun updateDirectory(): File {
        return File(filesDir, "updates").apply { mkdirs() }
    }

    private fun startManualDownload(metadata: UpdateMetadata) {
        val destination = File(updateDirectory(), "update_${metadata.versionCode}.apk")
        val installedVersionCode = getInstalledVersionCode()
        if (UpdateVerifier.verify(destination, metadata, installedVersionCode, packageManager)) {
            runOnUiThread { showVerifiedInstallDialog(destination, metadata, installedVersionCode) }
            return
        }

        Toast.makeText(this, "Mengunduh pembaruan...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                if (destination.exists()) destination.delete()

                val request = Request.Builder()
                    .url(metadata.updateUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                okClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Gagal unduh APK (${response.code()})")

                    val body = response.body() ?: throw Exception("File kosong")
                    FileOutputStream(destination).use { output ->
                        body.byteStream().copyTo(output)
                    }

                    if (UpdateVerifier.verify(destination, metadata, installedVersionCode, packageManager)) {
                        runOnUiThread { showVerifiedInstallDialog(destination, metadata, installedVersionCode) }
                    } else {
                        destination.delete()
                        throw Exception("Identitas atau signature APK tidak valid.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getInstalledVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun installApk(file: File) {
        try {
            if (!file.isFile || !file.canonicalPath.startsWith(updateDirectory().canonicalPath + File.separator)) return

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