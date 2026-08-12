package com.am2.am2.update

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val updateUrl: String,
    val sha256: String,
    val signerSha256: String,
    val changelog: String
) {
    companion object {
        private const val APPROVED_URL = "https://apiapi.am2-poc.com/update/update.apk"
        private val DIGEST = Regex("^[0-9a-f]{64}$")

        fun parse(raw: String): UpdateMetadata {
            val json = JSONObject(raw)
            val code = json.getLong("version_code")
            require(code > 0 && json.get("version_code") is Number) { "version_code invalid" }
            val name = json.getString("version_name").trim()
            val url = json.optString("update_url", json.optString("download_url", "")).trim()
            val sha = normalize(json.getString("sha256"))
            val signer = normalize(json.getString("signer_sha256"))
            require(name.isNotEmpty()) { "version_name invalid" }
            require(url == APPROVED_URL && URI(url).scheme == "https") { "update_url not approved" }
            require(DIGEST.matches(sha)) { "sha256 invalid" }
            require(DIGEST.matches(signer)) { "signer_sha256 invalid" }
            return UpdateMetadata(code, name, url, sha, signer, json.optString("changelog", ""))
        }

        fun normalize(value: String): String = value.replace(":", "").trim().lowercase()
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
