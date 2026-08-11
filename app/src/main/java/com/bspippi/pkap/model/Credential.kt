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
    val secret: String,           // hash or password
    val hashcatLine: String,      // ready for hashcat
    val source: String = "",      // IP:port or filename
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
}
