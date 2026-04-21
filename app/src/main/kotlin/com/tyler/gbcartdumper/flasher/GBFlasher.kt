package com.tyler.gbcartdumper.flasher

import com.tyler.gbcartdumper.flasher.Protocol.BYTE_ACK
import com.tyler.gbcartdumper.flasher.Protocol.BYTE_DATA
import com.tyler.gbcartdumper.flasher.Protocol.BYTE_END
import com.tyler.gbcartdumper.flasher.Protocol.BYTE_NAK
import com.tyler.gbcartdumper.flasher.Protocol.CMD_CONFIG
import com.tyler.gbcartdumper.flasher.Protocol.CMD_STATUS
import com.tyler.gbcartdumper.flasher.Protocol.DATA_LAST
import com.tyler.gbcartdumper.flasher.Protocol.FRAME_SIZE
import com.tyler.gbcartdumper.flasher.Protocol.OP_RROM
import com.tyler.gbcartdumper.flasher.Protocol.PACKET_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * High-level GB-Cart-Flasher operations (STATUS, dump ROM) riding on top of [FtdiTransport].
 *
 * Wire layout of every 72-byte packet:
 *   [0]     = DATA marker (0x55)
 *   [1]     = sub-command / data-type
 *   [2..69] = 68 bytes of payload (exact meaning per sub-command)
 *   [70]    = CRC-16 high byte
 *   [71]    = CRC-16 low byte
 *
 * Handshakes going the opposite direction are single bytes: ACK (0xAA), NAK (0xF0), END (0x0F).
 */
class GBFlasher(private val io: FtdiTransport, private val log: (String) -> Unit = {}) {

    /** Result of a STATUS query — the firmware's best-effort cart ID. */
    data class Status(
        val manufacturerId: Int,
        val chipId: Int,
        val gameName: String,
        val cartType: Int,
        val romSizeCode: Int,
        val ramSizeCode: Int,
        val raw: ByteArray
    )

    /** Ask the flasher to probe the inserted cart. MBC can be AUTO here. */
    suspend fun readStatus(mbc: Byte = Protocol.MBC_AUTO): Status = withContext(Dispatchers.IO) {
        io.flushInput()
        val req = buildPacket(CMD_STATUS) {
            it[2] = 0                 // id (unused on request)
            it[3] = mbc
            it[4] = Protocol.ALG_16
        }
        sendPacketWithRetry(req, label = "STATUS")
        val resp = receivePacket()
        // Device expects a single-byte ACK for the status response.
        io.writeByte(BYTE_ACK)

        val name = buildString {
            for (i in 9 until 9 + 16) {
                val b = resp[i].toInt() and 0xFF
                if (b == 0) break
                if (b in 0x20..0x7E) append(b.toChar())
            }
        }.trim()

        Status(
            manufacturerId = resp[2].toInt() and 0xFF,
            chipId = resp[3].toInt() and 0xFF,
            gameName = name,
            cartType = resp[25].toInt() and 0xFF,
            romSizeCode = resp[26].toInt() and 0xFF,
            ramSizeCode = resp[27].toInt() and 0xFF,
            raw = resp
        )
    }

    /**
     * Dump the cart's ROM. Emits progress (bytesRead, totalBytes) via [onProgress].
     *
     * [romBanks] is the number of 16 KiB pages to request (derived from the cart header).
     * The firmware will stream `PACKETS_PER_PAGE * romBanks` data packets; we ACK each one.
     */
    suspend fun readRom(
        mbc: Byte,
        romBanks: Int,
        onProgress: (bytesRead: Int, total: Int) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        require(romBanks in 1..512) { "Unreasonable bank count $romBanks" }
        val total = romBanks * Protocol.PAGE_SIZE
        val out = ByteArray(total)

        io.flushInput()
        val cfg = buildPacket(CMD_CONFIG) {
            it[2] = OP_RROM
            it[3] = mbc
            it[4] = Protocol.ALG_16
            it[5] = 0  // dap (doubled-address-pin; only relevant for MBC5-rumble flash writes)
            val pagesMinusOne = romBanks - 1
            it[6] = ((pagesMinusOne ushr 8) and 0xFF).toByte()
            it[7] = (pagesMinusOne and 0xFF).toByte()
        }
        sendPacketWithRetry(cfg, label = "CONFIG RROM")

        var received = 0
        var expectedPktNumByte: Int = 0   // firmware pkt_num wraps 0..255 inside a page
        var sawLast = false
        while (received < total && !sawLast) {
            val pkt = try {
                receivePacket()
            } catch (e: IOException) {
                io.writeByte(BYTE_NAK)
                throw e
            }

            val dataType = pkt[1]
            val pktNum = pkt[3].toInt() and 0xFF

            // Duplicate detection: firmware may re-send when its ACK was missed.
            if (received > 0 && pktNum != expectedPktNumByte) {
                // Off-by-one: accept prev-slot retransmission silently, re-ACK.
                val prev = (expectedPktNumByte - 1) and 0xFF
                if (pktNum == prev) {
                    io.writeByte(BYTE_ACK)
                    continue
                }
                io.writeByte(BYTE_NAK)
                throw IOException("Out-of-order packet: got $pktNum expected $expectedPktNumByte")
            }

            System.arraycopy(pkt, 6, out, received, FRAME_SIZE)
            received += FRAME_SIZE
            expectedPktNumByte = (expectedPktNumByte + 1) and 0xFF
            onProgress(received, total)
            io.writeByte(BYTE_ACK)
            if (dataType == DATA_LAST) sawLast = true
        }

        if (received != total) {
            io.writeByte(BYTE_END)
            throw IOException("Short dump: $received of $total bytes")
        }
        out
    }

    // --- internals ---------------------------------------------------------

    private fun buildPacket(subCommand: Byte, fillPayload: (ByteArray) -> Unit): ByteArray {
        val p = ByteArray(PACKET_SIZE)
        p[0] = BYTE_DATA
        p[1] = subCommand
        fillPayload(p)
        val crc = Crc16.compute(p, PACKET_SIZE - 2)
        p[PACKET_SIZE - 2] = ((crc ushr 8) and 0xFF).toByte()
        p[PACKET_SIZE - 1] = (crc and 0xFF).toByte()
        return p
    }

    private suspend fun sendPacketWithRetry(packet: ByteArray, label: String) {
        var tries = 0
        while (true) {
            io.write(packet)
            val reply = io.readExact(1, deadlineMs = 3000)[0]
            when (reply) {
                BYTE_ACK -> return
                BYTE_NAK -> {
                    tries++
                    if (tries >= 10) throw IOException("$label rejected 10× by flasher (check cart seating / baud)")
                    log("$label NAK — retry $tries")
                }
                BYTE_END -> throw IOException("$label aborted by flasher (END)")
                else -> throw IOException("$label got unexpected reply 0x${"%02X".format(reply.toInt() and 0xFF)}")
            }
        }
    }

    /**
     * Resync-on-garbage receive: scan forward until the DATA marker appears,
     * then pull the remaining 71 bytes and verify the CRC.
     */
    private suspend fun receivePacket(): ByteArray {
        // Scan for the leading DATA byte (up to PACKET_SIZE of slop).
        var probe = io.readExact(1)
        var scanned = 0
        while (probe[0] != BYTE_DATA) {
            scanned++
            if (scanned > PACKET_SIZE) throw IOException("No DATA marker in stream")
            probe = io.readExact(1)
        }
        val rest = io.readExact(PACKET_SIZE - 1)
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = BYTE_DATA
        System.arraycopy(rest, 0, packet, 1, PACKET_SIZE - 1)

        val expected = ((packet[PACKET_SIZE - 2].toInt() and 0xFF) shl 8) or (packet[PACKET_SIZE - 1].toInt() and 0xFF)
        val actual = Crc16.compute(packet, PACKET_SIZE - 2)
        if (expected != actual) {
            throw IOException("CRC mismatch (want 0x${"%04X".format(expected)}, got 0x${"%04X".format(actual)})")
        }
        return packet
    }
}
