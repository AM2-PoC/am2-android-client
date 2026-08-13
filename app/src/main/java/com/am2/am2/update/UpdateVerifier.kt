package com.am2.am2.update

import android.content.pm.PackageManager
import android.os.Build
import com.am2.am2.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object UpdateVerifier {
    private const val EXPECTED_PACKAGE = "com.am2.tik"

    fun verify(
        file: File,
        metadata: UpdateMetadata,
        installedVersionCode: Long,
        packageManager: PackageManager
    ): Boolean {
        var valid = false
        try {
            if (!file.isFile || file.length() < 100 * 1024L) return false
            if (metadata.versionCode <= installedVersionCode) return false
            if (sha256(file) != metadata.sha256) return false

            val flags = PackageManager.GET_SIGNATURES or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
            @Suppress("DEPRECATION")
            val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
            if (archive.packageName != EXPECTED_PACKAGE) return false
            @Suppress("DEPRECATION")
            val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
            if (archiveVersion != metadata.versionCode) return false

            val approved = UpdateMetadata.normalize(BuildConfig.APPROVED_UPDATE_SIGNER_SHA256)
            if (approved.length != 64 || approved != metadata.signerSha256) return false
            val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = archive.signingInfo ?: return false
                signingInfo.apkContentsSigners.toList()
            } else {
                @Suppress("DEPRECATION")
                archive.signatures?.toList().orEmpty()
            }
            valid = certificates.any { signature -> sha256(signature.toByteArray()) == approved }
            return valid
        } catch (_: Exception) {
            return false
        } finally {
            if (!valid) file.delete()
        }
    }

    fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
