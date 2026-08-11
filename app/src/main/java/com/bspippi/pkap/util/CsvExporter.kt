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
     * Pretty CSV with proper quoting.
     * Columns: timestamp, type, protocol, username, domain, secret, hashcat_line, source
     */
    fun export(context: Context, credentials: List<Credential>): File {
        val dir = File(context.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val file = File(dir, "pkap_credentials_${fileFmt.format(Date())}.csv")

        file.bufferedWriter().use { w ->
            // header
            w.write("timestamp,type,protocol,username,domain,secret,hashcat_line,source\n")

            credentials.forEach { c ->
                val ts = dateFmt.format(Date(c.timestamp))
                val line = listOf(
                    ts,
                    c.type.name,
                    c.protocol,
                    c.username,
                    c.domain,
                    c.secret,
                    c.hashcatLine,
                    c.source
                ).joinToString(",") { escape(it) }
                w.write(line)
                w.newLine()
            }
        }
        return file
    }

    /** Also write a live-updating CSV that Auto mode can keep open. */
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
            cred.secret,
            cred.hashcatLine,
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
