# GB Cart Dumper

Android app that dumps Game Boy and Game Boy Color cartridges to `.gb` / `.gbc` files
using the **jrodrigo GB Cart Flasher rev.c** plugged straight into the phone's USB-C port.

Requires Android 10+ (minSdk 29) on a device with USB host (OTG) support, and the flasher's
FTDI FT232R chip (VID `0x0403` / PID `0x6001`).

**Status:** working (v1.0.3, versionCode 4); 1.0.3 adds FleetReporter crash, freeze and error reports, the
first code change since mid-2026. ROM dumping only, with no save-RAM read/write or flashing. Full details are
in [PROJECT-DOCUMENTATION.md](PROJECT-DOCUMENTATION.md).

## How it talks to the flasher

The flasher uses an ATmega8515 MCU + FT232RL USB-to-serial bridge. The on-wire protocol
(original firmware) is:

| Detail            | Value                                                |
|-------------------|------------------------------------------------------|
| USB VID / PID     | `0x0403` / `0x6001` (standard FTDI)                  |
| Serial line       | 8N1, no flow control (DTR/RTS left de-asserted)      |
| Baud              | Firmware rates 125000 / 187500 / 375000 (jumper); 185000 is accepted as an alias of 187500. The app starts at 375000 and can auto-detect |
| Packet            | 72 bytes, CRC-16 (poly 0x1021, init 0) in the last two bytes, big-endian |
| Frame payload     | 64 bytes per data packet (256 packets = one 16 KiB bank) |
| Markers           | `DATA=0x55`, `ACK=0xAA`, `NAK=0xF0`, `END=0x0F`     |
| Sub-commands      | `CONFIG=0x00`, `DATA_NORMAL=0x01`, `DATA_LAST=0x02`, `ERASE=0x03`, `STATUS=0x04` |
| Operations        | `RROM=0x00`, `RRAM=0x01`, `WROM=0x02`, `WRAM=0x03`  |

Dump sequence for a ROM:
1. Host → `[0x55, 0x00 CONFIG, 0x00 RROM, mbc, alg, 0x00, (pages-1)_hi, (pages-1)_lo, 0…, crc16_hi, crc16_lo]`
2. Device → streams `pages × 256` data packets of 64 bytes each. The first data packet also
   acknowledges the CONFIG; there is no separate ACK.
3. Host ACKs every good packet and NAKs a bad CRC to get a retransmit. The final packet is marked `DATA_LAST=0x02`.

See `app/src/main/kotlin/com/tyler/gbcartdumper/flasher/` for the Kotlin implementation, and
`tools/` for the PC-side pyserial probe scripts used to verify it.

## Building

Requires Android Studio Ladybug+ (or the command line), JDK 17 and Android SDK 35.

```bash
./gradlew :app:assembleDebug
```

The resulting APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Plug the flasher into the phone via USB-C OTG (or native USB-C)
2. Grant USB permission when prompted
3. Tap **Auto-detect baud + MBC** (or pick them manually). The working combination is remembered per flasher
4. Tap **Choose** to pick a destination folder (SAF tree picker)
5. Tap **Scan cart** — the app reads the first 32 KiB (banks 0–1) and parses the cart header (title, MBC, ROM/RAM size, checksum, logo check)
6. Tap **Dump ROM** — the full ROM is streamed, its global checksum is checked (a mismatch only logs a warning) and it is saved as `<Title>-<yyyyMMdd-HHmmss>.gb[c]`

## Crash and error reports (FleetReporter)

`app/src/main/kotlin/com/tyler/gbcartdumper/fleet/FleetReporter.kt`, installed in `GBCartDumperApp.onCreate()`,
reports crashes, freezes (ANR: main thread blocked over 5 s) and errors/warnings the app logs on purpose
(`FleetReporter.error()` / `warn()`; no call sites yet) to FleetGuardian on the same phone, through its content
provider (no network, no permission). FleetGuardian forwards them to the Jarvis server's Reports, and a fatal one
can start an automatic fix. Text is redacted (emails, tokens, secrets) before it leaves the app. With no
FleetGuardian 0.3.0+ on the phone, reports wait in a queue of at most 50 (`filesDir/fleetreporter/queue`) and
nothing else changes. Spec: FleetGuardian `docs/fleet-reporter/README.md`.

## License

No license has been chosen yet, so all rights are reserved by default. See
[PROJECT-DOCUMENTATION.md](PROJECT-DOCUMENTATION.md#third-party--licensing) and
[THIRD-PARTY.md](THIRD-PARTY.md) for third-party and trademark notes. "Game Boy" is a trademark of
Nintendo; this project is not affiliated with Nintendo.

## Credits

Protocol details reverse-engineered from:
- Original jrodrigo firmware (`rev.c`)
- [Nold360/gbcflsh](https://github.com/Nold360/gbcflsh) Qt host
- [Tauwasser/GBCartFlasher](https://github.com/Tauwasser/GBCartFlasher) modern firmware rewrite
- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — FT232R driver on Android
