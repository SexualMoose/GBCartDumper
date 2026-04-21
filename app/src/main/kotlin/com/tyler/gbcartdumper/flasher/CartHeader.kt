package com.tyler.gbcartdumper.flasher

/**
 * Parses the 50-byte Game Boy cartridge header that lives at 0x0100–0x014F of ROM bank 0.
 * See: https://gbdev.io/pandocs/The_Cartridge_Header.html
 */
data class CartHeader(
    val title: String,
    val isColorOnly: Boolean,   // CGB flag 0xC0
    val isColorCapable: Boolean, // CGB flag 0x80 or 0xC0
    val cartridgeType: Int,      // byte 0x0147
    val romBanks: Int,           // derived from byte 0x0148
    val ramBanks: Int,           // derived from byte 0x0149
    val headerChecksumOk: Boolean,
    val globalChecksum: Int
) {
    val extension: String get() = if (isColorCapable) "gbc" else "gb"
    val romBytes: Int get() = romBanks * 16 * 1024
    val ramBytes: Int get() = ramBanks * 8 * 1024
    val mbc: Protocol.Mbc get() = Protocol.Mbc.fromHeader(cartridgeType)

    companion object {
        /** Given a buffer containing at least the first 0x150 bytes of ROM bank 0, parse it. */
        fun parse(rom: ByteArray): CartHeader {
            require(rom.size >= 0x150) { "Need at least 0x150 bytes to parse header" }
            val cgbFlag = rom[0x0143].toInt() and 0xFF
            val titleEnd = if (cgbFlag == 0x80 || cgbFlag == 0xC0) 0x013F else 0x0143
            val title = buildString {
                for (i in 0x0134..titleEnd) {
                    val b = rom[i].toInt() and 0xFF
                    if (b == 0) break
                    if (b in 0x20..0x7E) append(b.toChar())
                }
            }.trim()

            val cartType = rom[0x0147].toInt() and 0xFF
            val romBanks = when (val b = rom[0x0148].toInt() and 0xFF) {
                in 0x00..0x08 -> 2 shl b          // 0→2, 1→4, 2→8, ... 8→512
                0x52 -> 72
                0x53 -> 80
                0x54 -> 96
                else -> 2
            }
            val ramBanks = when (rom[0x0149].toInt() and 0xFF) {
                0x00 -> 0
                0x01 -> 1  // historically 2 KiB, treat as one 8 KiB bank
                0x02 -> 1
                0x03 -> 4
                0x04 -> 16
                0x05 -> 8
                else -> 0
            }

            // Header checksum: x = 0; for i in 0x134..0x14C: x = x - rom[i] - 1; x low byte == rom[0x14D]
            var hc = 0
            for (i in 0x0134..0x014C) hc = hc - (rom[i].toInt() and 0xFF) - 1
            val headerOk = (hc and 0xFF) == (rom[0x014D].toInt() and 0xFF)
            val gc = ((rom[0x014E].toInt() and 0xFF) shl 8) or (rom[0x014F].toInt() and 0xFF)

            return CartHeader(
                title = title,
                isColorOnly = cgbFlag == 0xC0,
                isColorCapable = cgbFlag == 0x80 || cgbFlag == 0xC0,
                cartridgeType = cartType,
                romBanks = romBanks,
                ramBanks = ramBanks,
                headerChecksumOk = headerOk,
                globalChecksum = gc
            )
        }
    }
}
