package com.bspippi.pkap.model

import java.util.UUID

enum class CredType {
    NTLMv1, NTLMv2, KERBEROS, HTTP_BASIC, HTTP_FORM,
    FTP, SMTP, IMAP, POP3, SNMP, LDAP, MSSQL, CREDIT_CARD, OTHER
}

data class Credential(
    val id: String = UUID.randomUUID().toString(),
    val type: CredType,
    val username: String = "",
    val domain: String = "",
    val secret: String,
    val hashcatLine: String,
    val source: String = "",
    val protocol: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val rawContext: String = ""
) {
    val displayTitle: String
        get() = when {
            username.isNotBlank() && domain.isNotBlank() -> "$domain\\$username"
            username.isNotBlank() -> username
            else -> type.name
        }

    val shortSecret: String
        get() = if (secret.length > 48) secret.take(48) + "…" else secret

    /**
     * Privacy-first representation for UI, logs and automatic exports.
     * The extractor can still keep the value in memory for an explicitly requested local export.
     */
    val redactedSecret: String
        get() = redact(secret)

    val redactedHashcatLine: String
        get() = if (hashcatLine.isBlank()) "" else "[redacted:${type.name.lowercase()}]"

    val safeSummary: String
        get() = buildString {
            append(type.name)
            if (displayTitle.isNotBlank()) append(" · ").append(displayTitle)
            if (protocol.isNotBlank()) append(" · ").append(protocol)
            if (source.isNotBlank()) append(" · ").append(source)
        }

    private fun redact(value: String): String {
        if (value.isBlank()) return ""
        if (value.length <= 4) return "••••"
        return value.take(2) + "••••" + value.takeLast(2)
    }
}
