package com.tyler.gbcartdumper.flasher

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0x0000, no reflection, no xor-out).
 * Matches the table-driven implementation in the jrodrigo host tool and firmware.
 */
object Crc16 {
    private val TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) {
                c = if ((c and 0x8000) != 0) (c shl 1) xor 0x1021 else c shl 1
            }
            t[i] = c and 0xFFFF
        }
    }

    fun compute(buf: ByteArray, len: Int = buf.size): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = ((crc shl 8) and 0xFFFF) xor TABLE[((crc shr 8) xor (buf[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc and 0xFFFF
    }
}
