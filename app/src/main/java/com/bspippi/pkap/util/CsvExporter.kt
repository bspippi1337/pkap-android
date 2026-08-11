package com.bspippi.pkap.util

import android.content.Context
import com.bspippi.pkap.model.Credential
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * CSV export is redacted unless the caller explicitly requests local secret export.
     * Columns: timestamp, type, protocol, username, domain, secret, hashcat_line, source
     */
    fun export(
        context: Context,
        credentials: List<Credential>,
        includeSecrets: Boolean = false
    ): File {
        val dir = File(context.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val suffix = if (includeSecrets) "raw" else "redacted"
        val file = File(dir, "pkap_${suffix}_${fileFmt.format(Date())}.csv")

        file.bufferedWriter().use { w ->
            w.write("timestamp,type,protocol,username,domain,secret,hashcat_line,source\n")

            credentials.forEach { c ->
                val ts = dateFmt.format(Date(c.timestamp))
                val line = listOf(
                    ts,
                    c.type.name,
                    c.protocol,
                    c.username,
                    c.domain,
                    if (includeSecrets) c.secret else c.redactedSecret,
                    if (includeSecrets) c.hashcatLine else c.redactedHashcatLine,
                    c.source
                ).joinToString(",") { escape(it) }
                w.write(line)
                w.newLine()
            }
        }
        return file
    }

    /**
     * Continuous root-mode CSV is always sanitized. Raw exports require an explicit
     * foreground user action so background capture never leaves plaintext secrets behind.
     */
    fun appendLive(context: Context, cred: Credential, liveFile: File) {
        if (!liveFile.exists()) {
            liveFile.parentFile?.mkdirs()
            liveFile.writeText("timestamp,type,protocol,username,domain,secret,hashcat_line,source\n")
        }
        val ts = dateFmt.format(Date(cred.timestamp))
        val line = listOf(
            ts,
            cred.type.name,
            cred.protocol,
            cred.username,
            cred.domain,
            cred.redactedSecret,
            cred.redactedHashcatLine,
            cred.source
        ).joinToString(",") { escape(it) }
        liveFile.appendText(line + "\n")
    }

    private fun escape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
