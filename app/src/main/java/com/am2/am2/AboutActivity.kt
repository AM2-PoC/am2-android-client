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
import org.json.JSONObject
import com.am2.am2.update.UpdateCheck
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

    /** A dropped link is worth another try; a broken channel is not. */
    private val DOWNLOAD_ATTEMPTS = 3
    private val DOWNLOAD_RETRY_DELAY_MS = 1500L

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
                when (val outcome = UpdateVerifier.check(file, metadata, installedVersionCode, packageManager)) {
                    is UpdateCheck.Ok -> installApk(file)
                    is UpdateCheck.Refused -> {
                        reportRefusal(outcome.reason, metadata.versionCode, installedVersionCode)
                        Toast.makeText(this, "Update ditolak: ${outcome.reason}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("NANTI", null)
            .show()
    }

    /**
     * Say why an update was refused, where somebody other than the operator
     * can read it.
     *
     * A refusal lives in a Toast on a radio in somebody's hand, so a handset
     * that cannot update is a handset nobody can diagnose -- which is how one
     * spent a day being blamed on the build it was refusing. vox_level is the
     * precedent: three rounds of argument about VOX ended the moment the
     * handset reported its own numbers instead of being asked about them.
     *
     * Best effort by design. A refusal must never itself fail.
     */
    private fun reportRefusal(reason: String, offered: Long, installed: Long) {
        try {
            WebSocketManager.emit(
                "update_refused",
                JSONObject()
                    .put("reason", reason)
                    .put("offered", offered)
                    .put("installed", installed)
                    .put("sdk_int", Build.VERSION.SDK_INT)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}"),
            )
        } catch (_: Exception) {
        }
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
                /*
                 * The link this runs over drops. The handset's own diagnostics
                 * report "Software caused connection abort", and the relay
                 * measured its uplink stalling on six to twelve per cent of
                 * frames with gaps up to 3.4 seconds. A nine megabyte APK does
                 * not always arrive whole over that.
                 *
                 * Nothing used to check that it had: copyTo wrote whatever
                 * arrived, the digest then disagreed, and the operator was told
                 * the identity or signature of the APK was invalid -- about a
                 * file that was merely incomplete. The same build had installed
                 * the day before, when the link was better, which is exactly
                 * why this looked like a property of the build.
                 */
                var lastFailure: Exception? = null
                var attempt = 1
                while (attempt <= DOWNLOAD_ATTEMPTS) {
                    try {
                        downloadOnce(metadata, destination)
                        lastFailure = null
                        break
                    } catch (e: Exception) {
                        lastFailure = e
                        destination.delete()
                        if (attempt < DOWNLOAD_ATTEMPTS) {
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "Unduhan terputus, mencoba lagi (${attempt + 1}/$DOWNLOAD_ATTEMPTS)",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempt)
                        }
                    }
                    attempt += 1
                }
                lastFailure?.let { throw it }

                /*
                 * The reason, not a verdict. Eight checks used to arrive
                 * here as one sentence about signatures, and a handset
                 * refused an update whose certificate was afterwards proven
                 * identical to the build already installed -- with nothing
                 * on the device or off it able to say which check fired.
                 */
                when (val outcome = UpdateVerifier.check(destination, metadata, installedVersionCode, packageManager)) {
                    is UpdateCheck.Ok ->
                        runOnUiThread { showVerifiedInstallDialog(destination, metadata, installedVersionCode) }
                    is UpdateCheck.Refused -> {
                        destination.delete()
                        reportRefusal(outcome.reason, metadata.versionCode, installedVersionCode)
                        throw Exception("Update ditolak: ${outcome.reason}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * One attempt at the APK, which either arrives whole or raises.
     *
     * The count is compared with the length the server promised. Without that
     * the only thing that noticed a short file was the digest, and a digest
     * mismatch was reported as an identity or signature failure -- which is a
     * statement about the APK, not about the link that cut it in half.
     */
    private fun downloadOnce(metadata: UpdateMetadata, destination: File) {
        if (destination.exists()) destination.delete()

        val request = Request.Builder()
            .url(metadata.updateUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        okClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Gagal unduh APK (${response.code()})")
            val body = response.body() ?: throw Exception("File kosong")
            val promised = body.contentLength()
            val written = FileOutputStream(destination).use { output ->
                body.byteStream().copyTo(output)
            }
            if (promised >= 0 && written != promised) {
                throw Exception("Unduhan terputus: $written dari $promised byte")
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