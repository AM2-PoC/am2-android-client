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
        /**
         * The one URL this build accepts an update from.
         *
         * It was the production URL written as a literal and compiled into
         * every environment, so a staging build refused its own channel and
         * would only have taken an APK served from production — the exact
         * cross-environment hand-off that separate channels exist to prevent.
         */
        val approvedUrl: String get() = BuildConfig.UPDATE_APK_URL

        /**
         * Scheme and host, which is what "may this APK come from here" means.
         *
         * The whole URL used to have to match the compiled literal character
         * for character. Every handset already installed therefore accepted
         * exactly one path forever: change it and those units could never be
         * updated again by anything the operator has. They are not broken and
         * not reachable, which is the definition of stranded, and it is not a
         * thing a later release can undo.
         *
         * The origin still cannot move. An APK is fetched over https from the
         * one host this build trusts, and the path is the channel's to choose.
         */
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
