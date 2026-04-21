package com.tyler.gbcartdumper.flasher

/**
 * Parses the 50-byte Game Boy cartridge header that lives at 0x0100–0x014F of ROM bank 0.
 * See: https://gbdev.io/pandocs/The_Cartridge_Header.html
 */
data class CartHeader(
    val title: String,
    val rawTitleBytes: ByteArray,   // untouched 0x0134..titleEnd for Shift-JIS fallback
    val isColorOnly: Boolean,       // CGB flag 0xC0
    val isColorCapable: Boolean,    // CGB flag 0x80 or 0xC0
    val cartridgeType: Int,         // byte 0x0147
    val romBanks: Int,              // derived from byte 0x0148
    val ramBanks: Int,              // derived from byte 0x0149
    val headerChecksumOk: Boolean,
    val globalChecksum: Int
) {
    val extension: String get() = if (isColorCapable) "gbc" else "gb"
    val romBytes: Int get() = romBanks * 16 * 1024
    val ramBytes: Int get() = ramBanks * 8 * 1024
    val mbc: Protocol.Mbc get() = Protocol.Mbc.fromHeader(cartridgeType)

    /** Human-readable category for the cart type byte — used by the UI to
     *  warn about unsupported mappers before a dump is attempted. */
    val mbcSupportStatus: MbcSupport get() = when (cartridgeType) {
        0x00, 0x08, 0x09 -> MbcSupport.Supported            // ROM, ROM+RAM, ROM+RAM+BAT
        0x01, 0x02, 0x03 -> MbcSupport.Supported            // MBC1
        0x05, 0x06 -> MbcSupport.Supported                  // MBC2
        in 0x0F..0x13 -> MbcSupport.SupportedRomOnly        // MBC3 (RTC state not captured)
        in 0x19..0x1B -> MbcSupport.Supported               // MBC5
        in 0x1C..0x1E -> MbcSupport.Supported               // MBC5+Rumble
        0x0B, 0x0C, 0x0D -> MbcSupport.UnsupportedMmm01
        0x20 -> MbcSupport.UnsupportedMbc6
        0x22 -> MbcSupport.UnsupportedMbc7
        0xFC -> MbcSupport.UnsupportedPocketCamera
        0xFD -> MbcSupport.UnsupportedTama5
        0xFE -> MbcSupport.UnsupportedHuc3
        0xFF -> MbcSupport.UnsupportedHuc1
        else -> MbcSupport.Unknown
    }

    enum class MbcSupport(val label: String, val usable: Boolean) {
        Supported("Supported", true),
        SupportedRomOnly("Supported (RTC state not captured)", true),
        UnsupportedMmm01("Unsupported: MMM01 multicart", false),
        UnsupportedMbc6("Unsupported: MBC6 (Net de Get)", false),
        UnsupportedMbc7("Unsupported: MBC7 (accelerometer)", false),
        UnsupportedPocketCamera("Unsupported: GB Camera", false),
        UnsupportedTama5("Unsupported: TAMA5 (Tamagotchi)", false),
        UnsupportedHuc3("Unsupported: HuC3 (IR + RTC)", false),
        UnsupportedHuc1("Unsupported: HuC1", false),
        Unknown("Unknown cart type", true);
    }

    companion object {
        /**
         * The 48-byte Nintendo logo that every real GB/GBC cart carries at
         * 0x0104–0x0133. If we read these bytes back off a cart bus that's
         * genuinely connected, this sequence will match exactly — so it's
         * a bulletproof "is the baud & MBC right?" oracle.
         */
        val NINTENDO_LOGO: ByteArray = byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66, 0xCC.toByte(), 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83.toByte(), 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88.toByte(), 0x89.toByte(), 0x00, 0x0E,
            0xDC.toByte(), 0xCC.toByte(), 0x6E, 0xE6.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xD9.toByte(), 0x99.toByte(),
            0xBB.toByte(), 0xBB.toByte(), 0x67, 0x63, 0x6E, 0x0E, 0xEC.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xDC.toByte(), 0x99.toByte(), 0x9F.toByte(), 0xBB.toByte(), 0xB9.toByte(), 0x33, 0x3E,
        )

        /** True iff the 48 bytes at 0x0104 match the canonical logo. */
        fun hasValidNintendoLogo(bank0: ByteArray): Boolean {
            if (bank0.size < 0x0134) return false
            for (i in NINTENDO_LOGO.indices) {
                if (bank0[0x0104 + i] != NINTENDO_LOGO[i]) return false
            }
            return true
        }

        /** Given a buffer containing at least the first 0x150 bytes of ROM bank 0, parse it. */
        fun parse(rom: ByteArray): CartHeader {
            require(rom.size >= 0x150) { "Need at least 0x150 bytes to parse header" }
            val cgbFlag = rom[0x0143].toInt() and 0xFF
            val titleEnd = if (cgbFlag == 0x80 || cgbFlag == 0xC0) 0x013F else 0x0143
            val rawTitleLen = titleEnd - 0x0134 + 1
            val rawTitleBytes = rom.copyOfRange(0x0134, 0x0134 + rawTitleLen)
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
                rawTitleBytes = rawTitleBytes,
                isColorOnly = cgbFlag == 0xC0,
                isColorCapable = cgbFlag == 0x80 || cgbFlag == 0xC0,
                cartridgeType = cartType,
                romBanks = romBanks,
                ramBanks = ramBanks,
                headerChecksumOk = headerOk,
                globalChecksum = gc
            )
        }

        /**
         * Compute the 16-bit ROM global checksum (sum of every byte except the
         * two bytes at 0x014E/0x014F). Matches the header byte pair big-endian.
         */
        fun computeGlobalChecksum(rom: ByteArray): Int {
            var sum = 0
            for (i in rom.indices) {
                if (i == 0x014E || i == 0x014F) continue
                sum += rom[i].toInt() and 0xFF
            }
            return sum and 0xFFFF
        }

        /**
         * Heuristic: MBC1 multicarts (Bomberman Collection, Game Boy Gallery 3 JP,
         * Momotarou Collection) have a second copy of the Nintendo logo at
         * 0x40104 — one of the sub-games' bank-0. A single-game MBC1 cart never
         * does. Requires at least 256 KiB of ROM already read.
         */
        fun looksLikeMbc1Multicart(rom: ByteArray): Boolean {
            if (rom.size < 0x40104 + NINTENDO_LOGO.size) return false
            for (i in NINTENDO_LOGO.indices) {
                if (rom[0x40104 + i] != NINTENDO_LOGO[i]) return false
            }
            return true
        }
    }
}
