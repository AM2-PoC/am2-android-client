package com.am2.am2.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateMetadataTest {
    private val digest = "a".repeat(64)

    @Test fun acceptsStrictMetadata() {
        val metadata = UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"https://apiapi.am2-poc.com/update/update.apk","sha256":"$digest","signer_sha256":"$digest"}""")
        assertEquals(3L, metadata.versionCode)
    }

    @Test fun rejectsUnapprovedOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"https://evil.example/update.apk","sha256":"$digest","signer_sha256":"$digest"}""")
        }
    }

    @Test fun rejectsLegacyDownloadUrlOnly() {
        assertThrows(Exception::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","download_url":"https://apiapi.am2-poc.com/update/update.apk","sha256":"$digest","signer_sha256":"$digest"}""")
        }
    }

    @Test fun rejectsMissingDigest() {
        assertThrows(Exception::class.java) {
            UpdateMetadata.parse("""{"version_code":3,"version_name":"1.1.0","update_url":"https://apiapi.am2-poc.com/update/update.apk","signer_sha256":"$digest"}""")
        }
    }
}
