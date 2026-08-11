package com.am2.am2.logging

internal object LogSanitizer {
    private val sensitiveAssignments = Regex(
        "(?i)([\\\"]?)(username|user|identity|password|pass|token|api[_-]?key|authorization|session|cookie|lat|latitude|lon|lng|longitude)\\1\\s*[:=]\\s*([\\\"]?)([^\\s&,;\\}\"]+)\\3"
    )
    private val bearer = Regex("(?i)(?<=authorization=\\[REDACTED])\\s+Bearer\\s+[^\\s,;]+|\\bBearer\\s+[^\\s,;]+")
    private val query = Regex("([?&][^=&#\\s]+)=([^&#\\s]+)")

    fun sanitize(message: String): String {
        var result = message.replace('\r', ' ').replace('\n', ' ')
        result = bearer.replace(result, "Bearer [REDACTED]")
        result = sensitiveAssignments.replace(result) { "${it.groupValues[2]}=[REDACTED]" }
        result = query.replace(result) { "${it.groupValues[1]}=[REDACTED]" }
        return result.take(2048)
    }
}
