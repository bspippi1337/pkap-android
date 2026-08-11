package com.bspippi.pkap.extractor

import com.bspippi.pkap.model.CredType
import com.bspippi.pkap.model.Credential
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import android.util.Base64
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Port of the core Pcredz parsing logic to pure Kotlin.
 * Focused on the high-value extractors. No Python, no libpcap dependency.
 */
class CredentialExtractor(
    private val onCredential: (Credential) -> Unit,
    private val enableCc: Boolean = true
) {
    // NTLM challenge cache: key = remote IP or connection id
    private val ntlmChallenges = ConcurrentHashMap<String, ByteArray>()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    private val latin1 = Charset.forName("ISO-8859-1")

    fun processPayload(
        payload: ByteArray,
        srcIp: String = "",
        dstIp: String = "",
        srcPort: Int = 0,
        dstPort: Int = 0,
        isTcp: Boolean = true
    ) {
        if (payload.isEmpty()) return
        val source = "$srcIp:$srcPort → $dstIp:$dstPort"

        // Order matters for performance – cheap checks first
        tryHttpBasic(payload, source)
        tryFtp(payload, source)
        trySmtp(payload, source)
        tryPopImap(payload, source)
        trySnmp(payload, source)
        tryNtlm(payload, source, srcIp, dstIp)
        tryKerberos(payload, source, isTcp)
        if (enableCc) tryCreditCard(payload, source)
        tryHttpForm(payload, source)
    }

    // ─── HTTP Basic ───────────────────────────────────────────────
    private val httpBasicPattern = Pattern.compile(
        "Authorization:\\s*Basic\\s+([A-Za-z0-9+/=]+)",
        Pattern.CASE_INSENSITIVE
    )

    private fun tryHttpBasic(data: ByteArray, source: String) {
        val text = String(data, latin1)
        val m = httpBasicPattern.matcher(text)
        if (m.find()) {
            try {
                val decoded = String(Base64.decode(m.group(1), Base64.DEFAULT), StandardCharsets.UTF_8)
                val parts = decoded.split(":", limit = 2)
                if (parts.size == 2) {
                    val user = parts[0]
                    val pass = parts[1]
                    emit(
                        CredType.HTTP_BASIC,
                        user, "", pass,
                        "HTTP-Basic: $user:$pass",
                        source, "HTTP", "Authorization: Basic …"
                    )
                }
            } catch (_: Exception) {}
        }
    }

    // ─── HTTP Form / password fields ──────────────────────────────
    private val formPattern = Pattern.compile(
        "(?i)(?:^|&)([^&=]*(?:pass|pwd|password|passwd|token|secret|key|auth)[^&=]*)=([^&\\s]{3,})",
        Pattern.CASE_INSENSITIVE
    )

    private fun tryHttpForm(data: ByteArray, source: String) {
        val text = String(data, latin1)
        if (!text.contains("POST", ignoreCase = true) && !text.contains("Content-Type", ignoreCase = true)) return
        val m = formPattern.matcher(text)
        while (m.find()) {
            val field = m.group(1) ?: continue
            val value = m.group(2) ?: continue
            emit(
                CredType.HTTP_FORM,
                field, "", value,
                "HTTP-Form: $field=$value",
                source, "HTTP", field
            )
        }
    }

    // ─── FTP ──────────────────────────────────────────────────────
    private fun tryFtp(data: ByteArray, source: String) {
        val text = String(data, latin1)
        if (text.startsWith("USER ", ignoreCase = true)) {
            val user = text.substring(5).trim().takeWhile { it != '\r' && it != '\n' }
            if (user.isNotBlank()) {
                // store pending user per source (simplified)
                pendingFtpUsers[source] = user
            }
        } else if (text.startsWith("PASS ", ignoreCase = true)) {
            val pass = text.substring(5).trim().takeWhile { it != '\r' && it != '\n' }
            val user = pendingFtpUsers.remove(source) ?: "?"
            if (pass.isNotBlank()) {
                emit(CredType.FTP, user, "", pass, "FTP: $user:$pass", source, "FTP")
            }
        }
    }
    private val pendingFtpUsers = ConcurrentHashMap<String, String>()

    // ─── SMTP AUTH ────────────────────────────────────────────────
    private fun trySmtp(data: ByteArray, source: String) {
        val text = String(data, latin1)
        if (text.startsWith("AUTH LOGIN", ignoreCase = true) || text.startsWith("AUTH PLAIN", ignoreCase = true)) {
            // next packets will contain base64
            return
        }
        // simple base64 credential lines (AUTH LOGIN user then pass)
        try {
            val trimmed = text.trim()
            if (trimmed.length in 8..200 && trimmed.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), StandardCharsets.UTF_8)
                if (decoded.contains(":") || decoded.length in 3..64) {
                    emit(CredType.SMTP, "", "", decoded, "SMTP-Base64: $decoded", source, "SMTP")
                }
            }
        } catch (_: Exception) {}
    }

    // ─── POP / IMAP ───────────────────────────────────────────────
    private fun tryPopImap(data: ByteArray, source: String) {
        val text = String(data, latin1)
        when {
            text.startsWith("USER ", ignoreCase = true) -> {
                val user = text.substring(5).trim().takeWhile { it != '\r' }
                pendingPopUsers[source] = user
            }
            text.startsWith("PASS ", ignoreCase = true) -> {
                val pass = text.substring(5).trim().takeWhile { it != '\r' }
                val user = pendingPopUsers.remove(source) ?: "?"
                emit(CredType.POP3, user, "", pass, "POP3: $user:$pass", source, "POP3")
            }
            text.startsWith("LOGIN ", ignoreCase = true) -> {
                // IMAP LOGIN user pass
                val parts = text.substring(6).trim().split(Regex("\\s+"), limit = 3)
                if (parts.size >= 2) {
                    val user = parts[0].trim('"')
                    val pass = parts[1].trim('"')
                    emit(CredType.IMAP, user, "", pass, "IMAP: $user:$pass", source, "IMAP")
                }
            }
        }
    }
    private val pendingPopUsers = ConcurrentHashMap<String, String>()

    // ─── SNMP community ───────────────────────────────────────────
    private fun trySnmp(data: ByteArray, source: String) {
        if (data.size < 10) return
        // very simplified: look for community string after version
        try {
            if (data[0] == 0x30.toByte()) { // SEQUENCE
                // SNMPv1/v2c community is usually after version integer
                var i = 2
                while (i < data.size - 4) {
                    if (data[i] == 0x04.toByte()) { // OCTET STRING
                        val len = data[i + 1].toInt() and 0xFF
                        if (len in 1..32 && i + 2 + len <= data.size) {
                            val community = String(data, i + 2, len, latin1)
                            if (community.matches(Regex("[\\x20-\\x7E]+"))) {
                                emit(CredType.SNMP, "", "", community, "SNMP-Community: $community", source, "SNMP")
                                return
                            }
                        }
                    }
                    i++
                }
            }
        } catch (_: Exception) {}
    }

    // ─── NTLM ─────────────────────────────────────────────────────
    private fun tryNtlm(data: ByteArray, source: String, srcIp: String, dstIp: String) {
        // Look for NTLMSSP signature
        val sig = byteArrayOf(0x4E, 0x54, 0x4C, 0x4D, 0x53, 0x53, 0x50, 0x00) // NTLMSSP\0
        val idx = indexOf(data, sig)
        if (idx < 0) return

        val ntlm = data.copyOfRange(idx, data.size)
        if (ntlm.size < 12) return

        val msgType = ByteBuffer.wrap(ntlm, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int

        when (msgType) {
            2 -> { // Challenge
                if (ntlm.size >= 32) {
                    val challenge = ntlm.copyOfRange(24, 32)
                    ntlmChallenges[srcIp] = challenge
                    ntlmChallenges[dstIp] = challenge
                }
            }
            3 -> { // Authenticate (response)
                parseNtlmType3(ntlm, source, srcIp, dstIp)
            }
        }
    }

    private fun parseNtlmType3(data: ByteArray, source: String, srcIp: String, dstIp: String) {
        if (data.size < 52) return
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val lmLen = buf.getShort(12).toInt() and 0xFFFF
            val lmOff = buf.getShort(16).toInt() and 0xFFFF
            val ntLen = buf.getShort(20).toInt() and 0xFFFF
            val ntOff = buf.getShort(24).toInt() and 0xFFFF
            val domainLen = buf.getShort(28).toInt() and 0xFFFF
            val domainOff = buf.getShort(32).toInt() and 0xFFFF
            val userLen = buf.getShort(36).toInt() and 0xFFFF
            val userOff = buf.getShort(40).toInt() and 0xFFFF

            fun safeString(off: Int, len: Int): String {
                if (off + len > data.size || len <= 0) return ""
                return String(data, off, len, latin1).replace("\u0000", "")
            }

            fun safeHex(off: Int, len: Int): String {
                if (off + len > data.size || len <= 0) return ""
                return data.copyOfRange(off, off + len).joinToString("") { "%02X".format(it) }
            }

            val domain = safeString(domainOff, domainLen)
            val user = safeString(userOff, userLen)
            val lmHash = safeHex(lmOff, lmLen)
            val ntHash = safeHex(ntOff, ntLen)

            val challenge = ntlmChallenges[srcIp] ?: ntlmChallenges[dstIp] ?: ByteArray(8)
            val chalHex = challenge.joinToString("") { "%02X".format(it) }

            if (ntLen == 24) {
                // NTLMv1
                val line = "$user::$domain:$lmHash:$ntHash:$chalHex"
                emit(CredType.NTLMv1, user, domain, line, line, source, "NTLM")
            } else if (ntLen > 60) {
                // NTLMv2
                val ntProof = ntHash.take(32)
                val blob = ntHash.drop(32)
                val line = "$user::$domain:$chalHex:$ntProof:$blob"
                emit(CredType.NTLMv2, user, domain, line, line, source, "NTLM")
            }
        } catch (_: Exception) {}
    }

    // ─── Kerberos AS-REQ etype 23 (simplified) ────────────────────
    private fun tryKerberos(data: ByteArray, source: String, isTcp: Boolean) {
        // Look for ASN.1 markers of AS-REQ with etype 23 (RC4)
        // This is a simplified port of the original heuristics
        if (data.size < 50) return
        try {
            // Search for common Kerberos patterns
            val etype23 = byteArrayOf(0x17) // etype 23
            // Very rough: look for $krb5pa style later or specific offsets from original
            // For production you would use a proper ASN.1 decoder
            if (indexOf(data, byteArrayOf(0x0a, 0x17)) >= 0 || // msgtype + etype rough
                indexOf(data, byteArrayOf(0xa2.toByte(), 0x36, 0x04, 0x34)) >= 0) {
                // Placeholder – full port of ParseMSKerbv5TCP/UDP is long; keep structure
                // In real use we would extract the encrypted timestamp
            }
        } catch (_: Exception) {}
    }

    // ─── Credit cards (Luhn) ──────────────────────────────────────
    private val ccPattern = Pattern.compile(
        "(?<![0-9])([3456][0-9]{3}[\\s-]*[0-9]{4}[\\s-]*[0-9]{4}[\\s-]*[0-9]{4})(?![0-9])"
    )

    private fun tryCreditCard(data: ByteArray, source: String) {
        val text = String(data, latin1)
        val m = ccPattern.matcher(text)
        while (m.find()) {
            val raw = m.group(1)?.replace(Regex("[\\s-]"), "") ?: continue
            if (raw.length in 13..19 && luhn(raw)) {
                emit(CredType.CREDIT_CARD, "", "", raw, "CC: $raw", source, "CC")
            }
        }
    }

    private fun luhn(n: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in n.length - 1 downTo 0) {
            var d = n[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    // ─── helpers ──────────────────────────────────────────────────
    private fun emit(
        type: CredType,
        user: String,
        domain: String,
        secret: String,
        hashcat: String,
        source: String,
        proto: String,
        context: String = ""
    ) {
        val key = "$type|$user|$domain|$secret"
        if (!seen.add(key)) return
        onCredential(
            Credential(
                type = type,
                username = user,
                domain = domain,
                secret = secret,
                hashcatLine = hashcat,
                source = source,
                protocol = proto,
                rawContext = context
            )
        )
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun clearState() {
        ntlmChallenges.clear()
        pendingFtpUsers.clear()
        pendingPopUsers.clear()
        seen.clear()
    }
}
