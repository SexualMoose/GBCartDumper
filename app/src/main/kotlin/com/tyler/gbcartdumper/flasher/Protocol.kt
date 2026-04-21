package com.tyler.gbcartdumper.flasher

/**
 * Wire protocol for the jrodrigo GB Cart Flasher rev.c (original ATmega8515 + FT232RL firmware).
 *
 * Transport: FT232R at 8N1, default 185000 baud (jumper: 125000 / 187500 / 375000).
 * Packets are fixed 72 bytes with a CRC-16/CCITT-FALSE trailer. Small handshakes
 * (ACK / NAK / END) are single bytes, not packets.
 */
object Protocol {
    const val PACKET_SIZE = 72
    const val FRAME_SIZE = 64          // payload bytes per data packet
    const val PAGE_SIZE = 16 * 1024    // one GB ROM bank = 16 KiB = 256 frames
    const val PACKETS_PER_PAGE = PAGE_SIZE / FRAME_SIZE  // 256

    // Single-byte handshakes and the leading marker for 72-byte packets.
    const val BYTE_DATA: Byte = 0x55.toByte()
    const val BYTE_ACK: Byte = 0xAA.toByte()
    const val BYTE_NAK: Byte = 0xF0.toByte()
    const val BYTE_END: Byte = 0x0F.toByte()

    // Sub-command (packet[1]). Values come straight from the original rev.c
    // firmware / Nold360 const.h "Packet Types" enum.
    const val CMD_CONFIG: Byte = 0x00
    const val DATA_NORMAL: Byte = 0x01
    const val DATA_LAST: Byte = 0x02
    const val CMD_ERASE: Byte = 0x03
    const val CMD_STATUS: Byte = 0x04

    // Operation (packet[2]) inside a CONFIG packet.
    const val OP_RROM: Byte = 0x00
    const val OP_RRAM: Byte = 0x01
    const val OP_WROM: Byte = 0x02
    const val OP_WRAM: Byte = 0x03

    // Memory bank controller (MBC) codes understood by the firmware.
    const val MBC_AUTO: Byte = 0x00
    const val MBC_MBC1: Byte = 0x01
    const val MBC_MBC2: Byte = 0x02
    const val MBC_MBC3: Byte = 0x03
    const val MBC_ROMONLY: Byte = 0x04
    const val MBC_MBC5: Byte = 0x05
    const val MBC_RUMBLE: Byte = 0x06

    // Flash-write algorithm (only relevant for WROM; RROM accepts either).
    const val ALG_16: Byte = 0x00
    const val ALG_12: Byte = 0x01

    enum class Mbc(val code: Byte, val label: String) {
        Auto(MBC_AUTO, "Auto-detect"),
        RomOnly(MBC_ROMONLY, "ROM only (no MBC)"),
        Mbc1(MBC_MBC1, "MBC1"),
        Mbc2(MBC_MBC2, "MBC2"),
        Mbc3(MBC_MBC3, "MBC3"),
        Mbc5(MBC_MBC5, "MBC5"),
        Rumble(MBC_RUMBLE, "MBC5 Rumble");

        companion object {
            fun fromHeader(cartTypeByte: Int): Mbc = when (cartTypeByte) {
                0x00 -> RomOnly
                0x01, 0x02, 0x03 -> Mbc1
                0x05, 0x06 -> Mbc2
                0x0F, 0x10, 0x11, 0x12, 0x13 -> Mbc3
                0x19, 0x1A, 0x1B -> Mbc5
                0x1C, 0x1D, 0x1E -> Rumble
                else -> Auto
            }
        }
    }
}
