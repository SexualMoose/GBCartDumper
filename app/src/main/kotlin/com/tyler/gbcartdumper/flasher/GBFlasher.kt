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
 *   [1]     = sub-command (CONFIG=0x00, DATA_NORMAL=0x01, DATA_LAST=0x02, ERASE=0x03, STATUS=0x04)
 *   [2..69] = 68 bytes of payload
 *   [70]    = CRC-16 high byte
 *   [71]    = CRC-16 low byte
 *
 * Flow (STATUS, CONFIG+RROM):
 *   1. Host sends full 72-byte request packet.
 *   2. Device either replies with NAK (0xF0) on bad CRC — host retries — or with a
 *      full 72-byte DATA packet.
 *   3. Host validates CRC on the response; sends ACK (0xAA) if good, NAK (0xF0) to
 *      request a retransmit. Final data packet is marked DATA_LAST=0x02.
 */
class GBFlasher(private val io: FtdiTransport, private val log: (String) -> Unit = {}) {

    data class Status(
        val manufacturerId: Int,
        val chipId: Int,
        val gameName: String,
        val cartType: Int,
        val romSizeCode: Int,
        val ramSizeCode: Int,
        val raw: ByteArray
    )

    suspend fun readStatus(mbc: Byte = Protocol.MBC_AUTO): Status = withContext(Dispatchers.IO) {
        io.flushInput()
        val req = buildPacket(CMD_STATUS) {
            it[2] = 0
            it[3] = mbc
            it[4] = Protocol.ALG_16
        }
        val resp = sendCommandGetResponse(req, label = "STATUS")

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
     * Stream the cart's ROM. Emits progress (bytesRead, totalBytes) via [onProgress].
     *
     * [romBanks] is the number of 16 KiB pages to request (from the cart header).
     * The firmware streams exactly `PACKETS_PER_PAGE * romBanks` data packets and
     * we ACK each one.
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
            it[5] = 0
            val pagesMinusOne = romBanks - 1
            it[6] = ((pagesMinusOne ushr 8) and 0xFF).toByte()
            it[7] = (pagesMinusOne and 0xFF).toByte()
        }

        // The first DATA packet from the device is both "I accept CONFIG" and
        // the first frame — no separate ACK in between.
        var first = sendCommandGetResponse(cfg, label = "CONFIG RROM")
        var received = 0
        var sawLast = first[1] == DATA_LAST
        System.arraycopy(first, 6, out, received, FRAME_SIZE)
        received += FRAME_SIZE
        onProgress(received, total)

        var expectedPktNumByte = (first[3].toInt() + 1) and 0xFF

        while (received < total && !sawLast) {
            val pkt = try {
                receiveDataPacket()
            } catch (e: IOException) {
                throw e
            }
            val dataType = pkt[1]
            val pktNum = pkt[3].toInt() and 0xFF

            if (pktNum != expectedPktNumByte) {
                val prev = (expectedPktNumByte - 1) and 0xFF
                if (pktNum == prev) {
                    // Firmware re-sent the previous frame because it didn't see our ACK.
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

    /**
     * Send [packet] and read back whatever the flasher returns. The rev.c firmware
     * replies to a well-formed STATUS/CONFIG with a full DATA packet (not a bare ACK).
     * On bad CRC it replies NAK, on operator abort it replies END.
     * Sends ACK back on a valid reply, NAK to request re-send, and returns the packet.
     */
    private suspend fun sendCommandGetResponse(packet: ByteArray, label: String): ByteArray {
        var tries = 0
        while (true) {
            io.write(packet)
            val first = io.readExact(1)[0]
            when (first) {
                BYTE_DATA -> {
                    val pkt = completeDataPacket()
                    if (pkt == null) {
                        io.writeByte(BYTE_NAK)
                        tries++
                        if (tries >= 10) throw IOException("$label: CRC on response failed 10×")
                        log("$label: bad CRC on response, retrying ($tries)")
                    } else {
                        io.writeByte(BYTE_ACK)
                        return pkt
                    }
                }
                BYTE_NAK -> {
                    tries++
                    if (tries >= 10) throw IOException("$label: flasher NAK'd 10× (check baud / cart seated?)")
                    log("$label: flasher NAK — retry $tries")
                }
                BYTE_END -> throw IOException("$label: flasher sent END")
                else -> throw IOException("$label got unexpected reply 0x${"%02X".format(first.toInt() and 0xFF)}")
            }
        }
    }

    /** Read the next DATA packet from the stream (without sending our own command first). */
    private suspend fun receiveDataPacket(): ByteArray {
        while (true) {
            val first = io.readExact(1)[0]
            when (first) {
                BYTE_DATA -> {
                    val pkt = completeDataPacket()
                    if (pkt == null) {
                        io.writeByte(BYTE_NAK)
                    } else {
                        return pkt
                    }
                }
                BYTE_NAK -> continue          // stray; ignore
                BYTE_ACK -> continue          // stray; ignore
                BYTE_END -> throw IOException("Flasher sent END mid-stream")
                else -> continue              // resync — skip stray bytes
            }
        }
    }

    /**
     * Assumes we just read the leading 0x55 DATA marker. Pulls the remaining 71 bytes,
     * returns the full packet if CRC matches, null otherwise.
     */
    private suspend fun completeDataPacket(): ByteArray? {
        val rest = io.readExact(PACKET_SIZE - 1)
        val pkt = ByteArray(PACKET_SIZE)
        pkt[0] = BYTE_DATA
        System.arraycopy(rest, 0, pkt, 1, PACKET_SIZE - 1)
        val expected = ((pkt[PACKET_SIZE - 2].toInt() and 0xFF) shl 8) or
            (pkt[PACKET_SIZE - 1].toInt() and 0xFF)
        val actual = Crc16.compute(pkt, PACKET_SIZE - 2)
        return if (expected == actual) pkt else null
    }
}
