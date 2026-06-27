# GB Cart Dumper

Android app that dumps Game Boy / Game Boy Color cartridges to `.gb` / `.gbc` files by driving a jrodrigo "GB Cart Flasher rev.c" plugged into the phone over USB-C OTG.

## Overview / Purpose

GB Cart Dumper turns an Android phone into a standalone Game Boy cartridge backup tool. It speaks the original jrodrigo GB Cart Flasher rev.c serial protocol (ATmega8515 + FT232RL bridge) directly from the phone's USB host port — no PC required. The user picks an output folder, scans a cartridge to read its header, and dumps the full ROM to a file named after the cart title.

The repo also ships three standalone Python "probe" scripts under `tools/` that re-implement the same protocol on a PC. These were used during development to reverse-engineer/verify the wire protocol and baud behavior independent of the Android UI.

## Status

Working / actively maintained (not abandoned). Evidence:
- Last commit 2 days before audit (2026-06-25), 16 commits total, all with descriptive messages.
- Version `1.0.2` (versionCode 3); commit history shows iterative bug-fix work on real hardware quirks (MASKROM carts, baud sweep, FT232 buffer sizing, firmware wedge recovery).
- Code is complete end-to-end: device discovery, USB permission flow, baud auto-detect, header parsing, ROM streaming with ACK/NAK retransmit handling, SAF file output.
- No automated tests exist; correctness is validated against physical hardware and the embedded Nintendo-logo "oracle" check.

## Technical Requirements

- Languages: Kotlin (app), Python 3 (dev probe tools only).
- Build tooling: Android Gradle Plugin 8.7.3, Gradle 8.11.1 (via wrapper), Kotlin 2.1.0.
- Android SDK: compileSdk 35 / targetSdk 35, minSdk 29 (Android 10+). JDK 17.
- IDE: Android Studio Ladybug+ (per README) or any environment with Android SDK 35.
- Hardware: an Android device with USB host (OTG) support, plus a jrodrigo GB Cart Flasher rev.c (FTDI FT232R, USB VID 0x0403 / PID 0x6001) and a GB/GBC cartridge.
- Accounts/keys: none required to build a debug APK. Release signing is handled by an external "fleet" pipeline; no keystore lives in this repo.
- Python tools: require `pyserial` (`import serial`, `serial.tools.list_ports`).

## Dependencies (key libs + licenses)

App (Gradle, declared in `gradle/libs.versions.toml`):
- androidx.core:core-ktx 1.15.0 — Apache-2.0
- androidx.activity:activity-compose 1.9.3 — Apache-2.0
- androidx.lifecycle (runtime/viewmodel compose) 2.8.7 — Apache-2.0
- androidx.compose BOM 2024.12.01 + ui / material3 / material-icons-extended / tooling — Apache-2.0
- org.jetbrains.kotlinx:kotlinx-coroutines-core/android 1.9.0 — Apache-2.0
- androidx.documentfile:documentfile 1.0.1 — Apache-2.0
- com.github.mik3y:usb-serial-for-android 3.8.1 (via JitPack) — MIT

Build plugins: AGP 8.7.3 (Apache-2.0), Kotlin Android / Compose 2.1.0 (Apache-2.0).

Python tools: `pyserial` — BSD-3-Clause.

All dependencies are mainstream and reasonably current as of the audit date; none are known-vulnerable.

## Setup Instructions

```bash
# 1. Clone
git clone https://github.com/SexualMoose/GBCartDumper.git
cd GBCartDumper

# 2. Ensure JDK 17 and Android SDK 35 are installed.
#    Android Studio will provision these automatically on first open,
#    or create a local.properties with: sdk.dir=/path/to/Android/sdk
#    (local.properties is gitignored and must NOT be committed.)

# 3. (Optional) Python probe tools
python3 -m pip install pyserial
```

## Build & Run

```bash
# Debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install to a connected device
./gradlew :app:installDebug
```

Release builds: `app/build.gradle.kts` has `isMinifyEnabled = false` and defines NO signingConfig. The release variant is signed externally by the owner's "fleet" key pipeline (see commit 5d7f9a9), so an unmodified `assembleRelease` here produces an unsigned APK.

## Usage

1. Plug the GB Cart Flasher rev.c into the phone via USB-C OTG (or native USB-C).
2. Grant USB permission when prompted (a USB_DEVICE_ATTACHED filter for VID 1027 / PID 24577 also auto-launches the app).
3. Tap "Choose" to pick a destination folder (SAF tree picker; persisted permission).
4. Tap "Scan cart" — the app auto-detects baud, queries STATUS, reads bank 0, and parses the cart header (title, CGB flag, MBC type, ROM/RAM size, header checksum, Nintendo-logo validity).
5. Tap "Dump ROM" — the full ROM is streamed and saved as `<Title>-<timestamp>.gb` or `.gbc`.

Python probe tools (PC, for debugging a flasher):
```bash
python tools/probe_pc.py [COMPORT] [BAUD]       # send STATUS, decode response
python tools/probe_pc_rrom.py COM8 185000       # try reading bank 0
python tools/sweep_pc.py [COMPORT]              # baud x MBC compatibility sweep
```

## Architecture (components + data flow)

UI layer (Jetpack Compose, MVVM):
- `MainActivity.kt` — hosts Compose UI, USB permission BroadcastReceiver, keep-screen-on.
- `ui/DumperScreen.kt` — the single screen (device card, folder picker, baud dropdown, log card with copy/clear).
- `ui/DumperViewModel.kt` — orchestrates scan/dump coroutines, baud auto-detect, progress, logging.
- `ui/Theme.kt`, `ui/AccentState.kt` — Material 3 theming with randomized accent.

Flasher protocol layer (`flasher/`):
- `Protocol.kt` — packet constants (72-byte packets, 64-byte payload frames, markers, sub-commands, MBC/op codes).
- `Crc16.kt` — CRC-16/CCITT-FALSE table-driven implementation.
- `FtdiTransport.kt` — thin wrapper over usb-serial-for-android's FT232 driver (8N1, no flow control, DTR/RTS deliberately de-asserted to avoid holding the ATmega in reset, 2 ms latency timer via reflection).
- `GBFlasher.kt` — high-level STATUS and readRom operations: builds packets, sends, validates CRC, ACK/NAK per frame, handles re-transmits, recovers from firmware wedge (END + delay + flush).
- `CartHeader.kt` — parses the 50-byte GB header at 0x0100–0x014F; embeds the 48-byte Nintendo boot logo as a validity oracle; classifies MBC support.

Util: `util/FlasherPrefs.kt` — SharedPreferences (MODE_PRIVATE) storing last-known-good baud + MBC keyed by FTDI serial number.

Data flow: USB device attach → permission → FtdiTransport.open/configure → GBFlasher.readStatus → CartHeader.parse → GBFlasher.readRom (streamed, ACK per frame) → write bytes to SAF DocumentFile.

## Integrations & Interconnects

- Hardware: jrodrigo GB Cart Flasher rev.c (ATmega8515 MCU + FTDI FT232RL USB-to-serial bridge). This is the sole external system the app talks to; communication is local USB serial, no network.
- Library: mik3y/usb-serial-for-android (GitHub via JitPack) provides the FT232R USB-serial driver.
- Owner's "fleet" release-signing pipeline (referenced in commit 5d7f9a9 "migrate to fleet release key"). The signing config itself is not in this repo.
- Protocol knowledge derived (reverse-engineered, not copied) from: original jrodrigo rev.c firmware, Nold360/gbcflsh (Qt host), Tauwasser/GBCartFlasher (firmware rewrite) — all credited in the README.
- No cloud services, APIs, telemetry, or analytics. No network permission is requested.

## Configuration & Secrets

- No secrets are stored in the repo (verified by grep over tree + history). No `.env`, `google-services.json`, keystore, or service-account file present or ever committed.
- `local.properties` (Android SDK path) is required locally for CLI builds and is correctly gitignored.
- Release signing keys are supplied by the external fleet pipeline; they must never be committed here.
- Runtime config the app persists: per-flasher baud + MBC in private SharedPreferences `flasher_prefs` (non-sensitive).

## Testing

- No automated tests (no `app/src/test` or `androidTest`, no CI workflow).
- Manual / hardware-in-the-loop testing is the de-facto strategy; the embedded Nintendo-logo match in `CartHeader.hasValidNintendoLogo` acts as a self-check that a dump is real and the baud/MBC are correct.
- The `tools/*.py` probe scripts serve as manual protocol validation harnesses on a PC.

## Known Issues / TODO

- Documentation drift: the README's protocol table lists sub-command values `CONFIG=0x01, ERASE=0x02, STATUS=0x03` and frame payload "64 bytes", but the authoritative code (`Protocol.kt`) uses `CMD_CONFIG=0x00, DATA_NORMAL=0x01, DATA_LAST=0x02, CMD_ERASE=0x03, CMD_STATUS=0x04`. The README sub-command values are stale/incorrect. (Frame payload of 64 bytes is correct.) Also the `GBFlasher.kt` class-level KDoc says payload is "68 bytes" while the code/constant uses a 64-byte FRAME_SIZE.
- MBC3 RTC state is not captured (documented in `CartHeader.MbcSupport.SupportedRomOnly`).
- Unsupported mappers: MMM01 multicart, MBC6, MBC7, Pocket Camera, TAMA5, HuC3, HuC1 (the UI warns before dumping).
- MBC1 multicarts may alias sub-games together because the rev.c firmware doesn't mask bank 4 correctly (runtime warning emitted).
- RAM (save) dumping is not implemented in the UI; the protocol layer defines RRAM/WRAM ops but only ROM read is wired up.
- Release build has no signing config and minify disabled — relies entirely on the external fleet pipeline.

## Third-party & Licensing notes

- No LICENSE file in the repo. As-is, all rights reserved by default — a license should be added if redistribution is intended.
- Dependencies are all permissive (Apache-2.0 / MIT / BSD); usb-serial-for-android is MIT. No GPL/AGPL/copyleft dependency, so no viral-license obligation on the app's own source.
- No vendored or copied third-party source files. The Python probes and Kotlin protocol code are original re-implementations; protocol details were reverse-engineered and the upstream projects are credited, not pasted.
- IP flags:
  - The 48-byte Nintendo boot logo bitmap is embedded in `CartHeader.kt` (lines 62-69). Nintendo has asserted copyright/trademark over this logo. Including it for dump-validation is common practice in GB tooling but is technically a third-party copyrighted asset.
  - "Game Boy" / "Game Boy Color" are Nintendo trademarks used descriptively in the README and app name ("GB Cart Dumper"). Nominative/descriptive use, low risk; avoid implying Nintendo affiliation.
  - "GB Cart Flasher rev.c", "jrodrigo", "Nold360", "Tauwasser" are referenced as the hardware/firmware/host projects being interoperated with; cited appropriately.

## Security notes

- No secrets, tokens, keys, keystores, or credentials in the working tree or git history (verified via `git grep` and `git log --all --name-only`).
- No network usage at all: no INTERNET permission requested, no HTTP/URL calls, no analytics/telemetry. Attack surface is local USB only.
- AndroidManifest is minimal: only `android.hardware.usb.host` feature; the single exported activity is the launcher (required) and only handles MAIN + USB_DEVICE_ATTACHED intents. No exported services/receivers/providers.
- `allowBackup="false"` set. SharedPreferences use MODE_PRIVATE and store only non-sensitive baud/MBC values.
- Reflection is used narrowly to call FT232 `setLatencyTimer` — benign, no untrusted input.
- No eval/exec, no SQL, no WebView, no JS bridge, no disabled TLS (no TLS at all). Input from the USB device is bounds-checked and CRC-validated; bank counts are clamped to 1..512.
- Overall security posture: clean. No findings above informational.
