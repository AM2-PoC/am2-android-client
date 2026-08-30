package com.am2.am2.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.am2.am2.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Whether a downloaded APK may be installed, and when not, which check said so.
 *
 * This used to answer with a Boolean. Eight different questions collapsed into
 * it and AboutActivity turned the result into one sentence about signatures, so
 * a truncated download and a genuine certificate mismatch reached the operator
 * identically. A handset in the field then refused an update whose signing
 * certificate was afterwards proven byte-identical to the build already
 * installed, and nothing on the device or off it could name the reason.
 *
 * Every refusal is named now, and the name carries the value it saw, because
 * the person reading it is holding a radio and not a debugger.
 */
sealed class UpdateCheck {
    object Ok : UpdateCheck()

    /** [reason] is a stable identifier, not a sentence: it goes in bug reports. */
    data class Refused(val reason: String) : UpdateCheck()
}

object UpdateVerifier {

    fun check(
        file: File,
        metadata: UpdateMetadata,
        installedVersionCode: Long,
        packageManager: PackageManager
    ): UpdateCheck {
        var outcome: UpdateCheck = UpdateCheck.Refused("unknown")
        try {
            if (!BuildConfig.SELF_UPDATE_ENABLED) {
                outcome = UpdateCheck.Refused("self_update_disabled")
                return outcome
            }
            if (!file.isFile) {
                outcome = UpdateCheck.Refused("no_file")
                return outcome
            }
            if (file.length() < 100 * 1024L) {
                // The usual shape of a download that ended early.
                outcome = UpdateCheck.Refused("file_too_small_${file.length()}")
                return outcome
            }
            if (metadata.versionCode <= installedVersionCode) {
                outcome = UpdateCheck.Refused(
                    "not_newer_${metadata.versionCode}_vs_$installedVersionCode")
                return outcome
            }
            val digest = sha256(file)
            if (digest != metadata.sha256) {
                // Named apart from every signature check. This is what a
                // truncated or rewritten download looks like, and it was
                // previously reported to the operator as a signature problem.
                outcome = UpdateCheck.Refused("bytes_differ_${digest.take(12)}")
                return outcome
            }

            val flags = PackageManager.GET_SIGNATURES or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
            @Suppress("DEPRECATION")
            val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            if (archive == null) {
                outcome = UpdateCheck.Refused("unreadable_archive")
                return outcome
            }
            if (archive.packageName != BuildConfig.APPLICATION_ID) {
                outcome = UpdateCheck.Refused("wrong_package_${archive.packageName}")
                return outcome
            }
            @Suppress("DEPRECATION")
            val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
            if (archiveVersion != metadata.versionCode) {
                outcome = UpdateCheck.Refused("apk_version_${archiveVersion}_manifest_${metadata.versionCode}")
                return outcome
            }

            val approved = UpdateMetadata.normalize(BuildConfig.APPROVED_UPDATE_SIGNER_SHA256)
            if (approved.length != 64) {
                // Built without AM2_APPROVED_SIGNER_SHA256. Nothing the channel
                // can serve would ever satisfy this build, and until now it
                // said only that the signature was invalid.
                outcome = UpdateCheck.Refused("build_trusts_no_signer")
                return outcome
            }
            if (approved != metadata.signerSha256) {
                outcome = UpdateCheck.Refused("manifest_signer_${metadata.signerSha256.take(12)}")
                return outcome
            }

            val certificates = signersOf(archive)
            if (certificates.isEmpty()) {
                outcome = UpdateCheck.Refused("no_signer_in_apk")
                return outcome
            }
            val seen = certificates.map { sha256(it.toByteArray()) }
            if (!seen.contains(approved)) {
                outcome = UpdateCheck.Refused("apk_signer_${seen.first().take(12)}")
                return outcome
            }

            outcome = UpdateCheck.Ok
            return outcome
        } catch (e: Exception) {
            outcome = UpdateCheck.Refused("error_${e.javaClass.simpleName}")
            return outcome
        } finally {
            if (outcome !is UpdateCheck.Ok) file.delete()
        }
    }

    /**
     * The certificates in an APK, from whichever of the two APIs answered.
     *
     * GET_SIGNATURES is requested on every API level and was then read on none
     * above P: a null `signingInfo` returned outright, even where
     * `archive.signatures` held the very certificate the check needed. That is
     * a refusal caused by asking the wrong accessor, not by the APK.
     */
    private fun signersOf(archive: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = archive.signingInfo
            val modern = when {
                info == null -> emptyList()
                info.hasMultipleSigners() -> info.apkContentsSigners.orEmpty().toList()
                else -> info.signingCertificateHistory.orEmpty().toList()
            }
            if (modern.isNotEmpty()) return modern
        }
        @Suppress("DEPRECATION")
        return archive.signatures?.toList().orEmpty()
    }

    fun verify(
        file: File,
        metadata: UpdateMetadata,
        installedVersionCode: Long,
        packageManager: PackageManager
    ): Boolean = check(file, metadata, installedVersionCode, packageManager) is UpdateCheck.Ok

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
