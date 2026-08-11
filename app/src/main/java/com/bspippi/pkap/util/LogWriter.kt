package com.bspippi.pkap.util

import android.content.Context
import com.bspippi.pkap.model.CredType
import com.bspippi.pkap.model.Credential
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LogWriter(private val context: Context) {
    private val logsDirectory: File by lazy {
        File(context.getExternalFilesDir(null), "logs").also { it.mkdirs() }
    }

    private val sessionLog: File by lazy {
        File(logsDirectory, "PKap-Session.log")
    }

    private val written = ConcurrentHashMap.newKeySet<String>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Automatic persistence is sanitized by default. Raw material is only written when
     * includeSecrets=true is passed by an explicit user action.
     */
    fun write(cred: Credential, includeSecrets: Boolean = false) {
        val dedupValue = if (includeSecrets) cred.hashcatLine else cred.safeSummary
        val key = "${cred.type}|$dedupValue"
        if (!written.add(key)) return

        val persisted = if (includeSecrets) cred.hashcatLine else cred.redactedHashcatLine
        val line = buildString {
            append('[').append(dateFmt.format(Date(cred.timestamp))).append("] ")
            append(cred.safeSummary).append('\n')
            if (persisted.isNotBlank()) append(persisted).append('\n')
        }
        sessionLog.appendText(line)

        val filename = when (cred.type) {
            CredType.NTLMv1 -> "NTLMv1.txt"
            CredType.NTLMv2 -> "NTLMv2.txt"
            CredType.KERBEROS -> "MSKerb.txt"
            CredType.HTTP_BASIC -> "HTTP-Basic.txt"
            CredType.HTTP_FORM -> "HTTP-PasswordFields.txt"
            CredType.FTP -> "FTP-Plaintext.txt"
            CredType.SMTP -> "SMTP-Plaintext.txt"
            CredType.IMAP -> "IMAP-Plaintext.txt"
            CredType.POP3 -> "POP3-Plaintext.txt"
            CredType.SNMP -> "SNMP.txt"
            CredType.LDAP -> "LDAP-Simple.txt"
            CredType.MSSQL -> "MSSQL-Plaintext.txt"
            CredType.CREDIT_CARD -> "CreditCards.txt"
            else -> "Other.txt"
        }
        File(logsDirectory, filename).appendText((persisted.ifBlank { "[redacted]" }) + "\n")
    }

    fun getLogsDir(): File = logsDirectory

    fun clearSession() {
        written.clear()
    }
}
