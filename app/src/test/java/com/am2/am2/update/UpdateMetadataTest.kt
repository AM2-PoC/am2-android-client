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

    /**
     * The manifest the build publishes, in the shape `jq` emits it.
     *
     * Every test above proves the parser rejects a wrong shape. None proved
     * anything produced the right one -- and nothing did: deployment published
     * `download_url` and no digests, so the parse threw before a version was
     * ever compared and the check reported failure rather than "no update".
     * Pinning the producer's output here is what stops the two schemas drifting
     * apart again, in the direction that fails silently.
     */
    @Test fun acceptsTheManifestTheBuildPublishes() {
        val published = """
            {
              "version_code": 103,
              "version_name": "1.1.103-staging",
              "update_url": "${UpdateMetadata.approvedUrl}",
              "sha256": "f375bef739839635f3e9fd509a1d1b0661625ac4705c89deaac8d6381efff0b7",
              "signer_sha256": "768c13b8ff19985cc052bf4200e51dd49d6d3986bce609f462a0ffcfbfbdd2df",
              "changelog": "staging build from 5909b4de1a2b"
            }
        """.trimIndent()

        val metadata = UpdateMetadata.parse(published)

        assertEquals(103L, metadata.versionCode)
        assertEquals("1.1.103-staging", metadata.versionName)
        assertEquals("staging build from 5909b4de1a2b", metadata.changelog)
    }

    /**
     * A local build must never look newer than a published one.
     *
     * The version code now comes from the build, defaulting low when no build
     * number is supplied. That default is what keeps a developer APK from being
     * offered to a field device, so it is worth stating rather than assuming.
     */
    @Test fun anUnnumberedBuildCannotOutrankAPublishedOne() {
        assertTrue(
            "an unnumbered local build must stay below CI run numbers",
            BuildConfig.VERSION_CODE == 1 || BuildConfig.VERSION_CODE >= 100,
        )
    }
}
