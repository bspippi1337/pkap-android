package com.bspippi.pkap.parser

import com.bspippi.pkap.extractor.CredentialExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal pure-Kotlin PCAP (classic) reader.
 * Supports Ethernet, Linux cooked (SLL), Raw IP.
 */
class PcapParser(
    private val extractor: CredentialExtractor,
    private val onProgress: (Int, Int) -> Unit = { _, _ -> }
) {
    suspend fun parseFile(file: File) = withContext(Dispatchers.IO) {
        file.inputStream().use { parseStream(it, file.name) }
    }

    suspend fun parseStream(input: InputStream, name: String = "stream") = withContext(Dispatchers.IO) {
        val header = ByteArray(24)
        if (input.read(header) != 24) throw IllegalArgumentException("Invalid PCAP header")

        val magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val littleEndian = when (magic) {
            0xa1b2c3d4.toInt() -> true
            0xd4c3b2a1.toInt() -> false
            else -> throw IllegalArgumentException("Not a classic PCAP (magic=$magic)")
        }
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        // linktype at offset 20
        val linkType = ByteBuffer.wrap(header, 20, 4).order(order).int

        var packets = 0
        val pktHeader = ByteArray(16)

        while (true) {
            val read = input.read(pktHeader)
            if (read < 16) break

            val buf = ByteBuffer.wrap(pktHeader).order(order)
            val inclLen = buf.getInt(8)
            if (inclLen <= 0 || inclLen > 65535) break

            val packet = ByteArray(inclLen)
            var total = 0
            while (total < inclLen) {
                val r = input.read(packet, total, inclLen - total)
                if (r < 0) break
                total += r
            }
            if (total < inclLen) break

            processPacket(packet, linkType)
            packets++
            if (packets % 500 == 0) onProgress(packets, -1)
        }
        onProgress(packets, packets)
    }

    private fun processPacket(packet: ByteArray, linkType: Int) {
        var offset = when (linkType) {
            1 -> 14          // Ethernet
            113 -> 16        // Linux cooked SLL
            101, 12 -> 0     // Raw IP / loopback
            else -> 14       // fallback Ethernet
        }
        if (offset >= packet.size) return

        // IP version
        val verIhl = packet[offset].toInt() and 0xFF
        val version = verIhl ushr 4
        if (version != 4 && version != 6) return

        if (version == 4) {
            val ihl = (verIhl and 0x0F) * 4
            if (offset + ihl > packet.size) return
            val protocol = packet[offset + 9].toInt() and 0xFF
            val srcIp = (0..3).joinToString(".") { (packet[offset + 12 + it].toInt() and 0xFF).toString() }
            val dstIp = (0..3).joinToString(".") { (packet[offset + 16 + it].toInt() and 0xFF).toString() }
            val ipPayloadOff = offset + ihl

            when (protocol) {
                6 -> parseTcp(packet, ipPayloadOff, srcIp, dstIp)   // TCP
                17 -> parseUdp(packet, ipPayloadOff, srcIp, dstIp)  // UDP
            }
        }
        // IPv6 left as future enhancement
    }

    private fun parseTcp(packet: ByteArray, off: Int, srcIp: String, dstIp: String) {
        if (off + 20 > packet.size) return
        val srcPort = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        val dstPort = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
        val dataOff = ((packet[off + 12].toInt() and 0xF0) ushr 4) * 4
        val payloadOff = off + dataOff
        if (payloadOff >= packet.size) return
        val payload = packet.copyOfRange(payloadOff, packet.size)
        extractor.processPayload(payload, srcIp, dstIp, srcPort, dstPort, isTcp = true)
    }

    private fun parseUdp(packet: ByteArray, off: Int, srcIp: String, dstIp: String) {
        if (off + 8 > packet.size) return
        val srcPort = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        val dstPort = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
        val payloadOff = off + 8
        if (payloadOff >= packet.size) return
        val payload = packet.copyOfRange(payloadOff, packet.size)
        extractor.processPayload(payload, srcIp, dstIp, srcPort, dstPort, isTcp = false)
    }
}
