# GB Cart Dumper

Android app that dumps Game Boy and Game Boy Color cartridges to `.gb` / `.gbc` files
using the **jrodrigo GB Cart Flasher rev.c** plugged straight into the phone's USB-C port.

Built for the Samsung Galaxy S26 Ultra (Android 14+). Works on any Android device with USB
host support and the FTDI FT232R chip on the flasher.

## How it talks to the flasher

The flasher uses an ATmega8515 MCU + FT232RL USB-to-serial bridge. The on-wire protocol
(original firmware) is:

| Detail            | Value                                                |
|-------------------|------------------------------------------------------|
| USB VID / PID     | `0x0403` / `0x6001` (standard FTDI)                  |
| Serial line       | 8N1, no flow control                                 |
| Baud              | 185000 default; jumper can select 125000 / 187500 / 375000 |
| Packet            | 72 bytes, CRC-16/CCITT-FALSE in the last two bytes  |
| Frame payload     | 64 bytes per data packet (256 packets = one 16 KiB bank) |
| Markers           | `DATA=0x55`, `ACK=0xAA`, `NAK=0xF0`, `END=0x0F`     |
| Sub-commands      | `CONFIG=0x01`, `ERASE=0x02`, `STATUS=0x03`          |
| Operations        | `RROM=0x00`, `RRAM=0x01`, `WROM=0x02`, `WRAM=0x03`  |

Dump sequence for a ROM:
1. Host → `[0x55, 0x01 CONFIG, 0x00 RROM, mbc, alg, dap, pages_hi, pages_lo, …, crc16]`
2. Device → ACK, then streams `pages × 256` data packets of 64 bytes each
3. Host ACKs every packet. Final packet marked `DATA_LAST=0x02`.

See `app/src/main/kotlin/com/tyler/gbcartdumper/flasher/` for the Kotlin implementation.

## Building

Requires Android Studio Ladybug+ and Android SDK 35.

```bash
./gradlew :app:assembleDebug
```

The resulting APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Plug the flasher into the phone via USB-C OTG (or native USB-C)
2. Grant USB permission when prompted
3. Tap **Choose** to pick a destination folder (SAF tree picker)
4. Tap **Scan cart** — the app queries STATUS, then reads bank 0 to parse the cart header
5. Tap **Dump ROM** — full ROM is streamed and saved as `<Title>-<timestamp>.gb[c]`

## Credits

Protocol details reverse-engineered from:
- Original jrodrigo firmware (`rev.c`)
- [Nold360/gbcflsh](https://github.com/Nold360/gbcflsh) Qt host
- [Tauwasser/GBCartFlasher](https://github.com/Tauwasser/GBCartFlasher) modern firmware rewrite
- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — FT232R driver on Android
