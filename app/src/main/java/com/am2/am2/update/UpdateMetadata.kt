package com.am2.am2.update

import com.am2.am2.BuildConfig
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

        val approvedUrl: String get() = BuildConfig.UPDATE_APK_URL

        private fun origin(url: String): String {
            val end = url.indexOf('/', url.indexOf("//").let { if (it < 0) 0 else it + 2 })
            return (if (end < 0) url else url.substring(0, end)).trim().lowercase()
        }

        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val INTEGER = Regex("^-?[0-9]+$")

        fun parse(raw: String): UpdateMetadata {
            val json = JSONObject(raw)
            val rawCode = json.get("version_code")
            require(rawCode is Number && INTEGER.matches(rawCode.toString())) { "version_code invalid" }
            val code = rawCode.toString().toLong()
            val name = json.getString("version_name").trim()
            val url = json.getString("update_url").trim()
            val sha = normalize(json.getString("sha256"))
            val signer = normalize(json.getString("signer_sha256"))
            require(code > 0) { "version_code invalid" }
            require(name.isNotEmpty()) { "version_name invalid" }
            require(origin(url) == origin(approvedUrl)) { "update_url origin not approved" }
            require(url.startsWith("https://")) { "update_url not https" }
            require(DIGEST.matches(sha)) { "sha256 invalid" }
            require(DIGEST.matches(signer)) { "signer_sha256 invalid" }
            return UpdateMetadata(code, name, url, sha, signer, json.optString("changelog", ""))
        }

        fun normalize(value: String): String = value.replace(":", "").trim().lowercase()
    }
}
