package com.am2.am2.update

import org.json.JSONObject

data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val updateUrl: String,
    val sha256: String,
    val signerSha256: String,
    val changelog: String
) {
    companion object {
        const val APPROVED_URL = "https://apiapi.am2-poc.com/update/update.apk"
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val INTEGER = Regex("^-?[0-9]+$")

        fun parse(raw: String): UpdateMetadata {
            val json = JSONObject(raw)
            val rawCode = json.get("version_code")
            require(rawCode is Number && INTEGER.matches(rawCode.toString())) { "version_code invalid" }
            val code = rawCode.toString().toLong()
            val name = json.getString("version_name").trim()
            val url = json.optString("update_url", json.optString("download_url", "")).trim()
            val sha = normalize(json.getString("sha256"))
            val signer = normalize(json.getString("signer_sha256"))
            require(code > 0) { "version_code invalid" }
            require(name.isNotEmpty()) { "version_name invalid" }
            require(url == APPROVED_URL) { "update_url not approved" }
            require(DIGEST.matches(sha)) { "sha256 invalid" }
            require(DIGEST.matches(signer)) { "signer_sha256 invalid" }
            return UpdateMetadata(code, name, url, sha, signer, json.optString("changelog", ""))
        }

        fun normalize(value: String): String = value.replace(":", "").trim().lowercase()
    }
}
