package com.am2.am2.update

import com.am2.am2.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMetadataTest {
    private val digest = "a".repeat(64)

    @Test fun acceptsStrictMetadata() {
        val metadata = UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"${UpdateMetadata.approvedUrl}","sha256":"$digest","signer_sha256":"$digest"}""")
        assertEquals(3L, metadata.versionCode)
    }

    @Test fun rejectsUnapprovedOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"https://evil.example/update.apk","sha256":"$digest","signer_sha256":"$digest"}""")
        }
    }

    @Test fun rejectsLegacyDownloadUrlOnly() {
        assertThrows(Exception::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","download_url":"${UpdateMetadata.approvedUrl}","sha256":"$digest","signer_sha256":"$digest"}""")
        }
    }

    @Test fun rejectsMissingDigest() {
        assertThrows(Exception::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"${UpdateMetadata.approvedUrl}","signer_sha256":"$digest"}""")
        }
    }

    @Test fun approvedUrlBelongsToThisBuildsOwnEnvironment() {
        // The URL used to be a production literal in every variant, so a
        // staging build refused its own channel and would only have accepted
        // an APK served from production.
        assertEquals(BuildConfig.UPDATE_APK_URL, UpdateMetadata.approvedUrl)
        assertTrue(
            "approved URL must sit on this build's own manifest host",
            UpdateMetadata.approvedUrl.startsWith(
                BuildConfig.UPDATE_MANIFEST_URL.substringBeforeLast('/'),
            ),
        )
    }

    @Test fun rejectsAnUpdateOfferedFromAnotherEnvironment() {
        val foreign = if (UpdateMetadata.approvedUrl.contains("staging")) {
            UpdateMetadata.approvedUrl.replace("staging-", "")
        } else {
            UpdateMetadata.approvedUrl.replace("://", "://staging-")
        }
        assertThrows(Exception::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"$foreign","sha256":"$digest","signer_sha256":"$digest"}""")
        }
    }
}
