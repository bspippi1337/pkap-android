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
        File(logsDirectory, "CredentialDump-Session.log")
    }

    private val written = ConcurrentHashMap.newKeySet<String>()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun write(cred: Credential) {
        val key = "${cred.type}|${cred.hashcatLine}"
        if (!written.add(key)) return

        val line = "[${dateFmt.format(Date(cred.timestamp))}] ${cred.type} ${cred.source}\n${cred.hashcatLine}\n"
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
        File(logsDirectory, filename).appendText(cred.hashcatLine + "\n")
    }

    fun getLogsDir(): File = logsDirectory

    fun clearSession() {
        written.clear()
    }
}
