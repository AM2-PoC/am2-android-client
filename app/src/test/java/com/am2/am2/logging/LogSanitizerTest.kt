package com.am2.am2.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsCredentialsIdentityLocationAndQueryValues() {
        val input = "username=alice password=hunter2 token=abc Authorization: Bearer zzz lat=-6.2 lon=106.8 https://host/path?session=123&mode=ptt"
        val output = LogSanitizer.sanitize(input)

        listOf("alice", "hunter2", "abc", "zzz", "-6.2", "106.8", "123", "ptt").forEach {
            assertFalse("leaked $it in $output", output.contains(it))
        }
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun redactsJsonAndCookieValues() {
        val input = "{\"username\":\"alice\",\"password\":\"hunter2\",\"latitude\":-6.2} Cookie: session=abc123"
        val output = LogSanitizer.sanitize(input)

        listOf("alice", "hunter2", "-6.2", "abc123").forEach {
            assertFalse("leaked $it in $output", output.contains(it))
        }
    }

    @Test
    fun redactsUnlabelledCoordinatePairs() {
        val output = LogSanitizer.sanitize("IP Location found: -6.200000, 106.816666 (Jakarta)")

        assertFalse(output.contains("-6.200000"))
        assertFalse(output.contains("106.816666"))
    }

    @Test
    fun forcesSingleLineAndBoundsLength() {
        val output = LogSanitizer.sanitize("first\r\nsecond " + "x".repeat(3000))

        assertFalse(output.contains('\n'))
        assertFalse(output.contains('\r'))
        assertTrue(output.length <= 2048)
    }
}
