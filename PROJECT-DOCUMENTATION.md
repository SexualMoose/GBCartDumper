# GB Cart Dumper — Project Documentation

Android app that dumps Game Boy / Game Boy Color cartridge ROMs to `.gb` / `.gbc` files by driving a jrodrigo "GB Cart Flasher rev.c" plugged into the phone over USB-C OTG.

_Last audited: 2026-10-10 against main @ 3c18006; updated 2026-10-19 for **1.0.3 (versionCode 4)**, FleetReporter (branch `fleet-reporter`)._

## Overview / Purpose

GB Cart Dumper turns an Android phone into a standalone Game Boy cartridge backup tool. It speaks the original jrodrigo GB Cart Flasher rev.c serial protocol (ATmega8515 MCU + FTDI FT232RL USB-to-serial bridge) directly from the phone's USB host port — no PC required. The user auto-detects (or picks) the flasher's baud rate and the cart's memory bank controller (MBC), picks an output folder, scans the cartridge to read its header, and dumps the full ROM to a file named after the cart title.

The repo also ships three standalone Python probe scripts under `tools/` that re-implement the same protocol on a PC. They were used during development to verify the wire protocol and baud behaviour independently of the Android UI.

Implemented: device discovery + USB permission flow, baud × MBC auto-detect with per-flasher memory, bank-0 header scan, full ROM dump with ACK/NAK flow control, post-dump checksum and multicart checks, duplicate-file handling, SAF file output.
Not implemented: save-RAM (SRAM) read/write, flash writing, and erase. The protocol constants for those operations exist in `flasher/Protocol.kt` but nothing calls them.

## Status

**Working.** Per the commit history, the protocol and UI were developed and tested on-device; this audit did not re-test on hardware. 1.0.3 (versionCode 4, 2026-10-19) adds FleetReporter (see Crash and error reports); before that, no code had changed since mid-2026. Evidence:
- 17 commits on `main` (origin/main == HEAD `3c18006`). There are no other branches and no tags.
- Development: 14 commits on 2026-04-21 (initial commit through "Default baud 375000"). Their messages describe on-device fixes for MASKROM carts, the baud sweep, FT232 buffer sizing and firmware-wedge recovery.
- 2026-06-04 `71ee554`: new launcher icon, version 1.0.1 (vc2). 2026-06-25 `5d7f9a9`: migration to the fleet release key and a version bump to **1.0.2 (versionCode 3)**. 2026-06-27 `3c18006`: generated documentation.
- There are no automated tests and no CI.

## Technical Requirements

- Languages: Kotlin (app), Python 3.8+ (development probe tools only; they call `bytes.hex(sep)`, added in 3.8).
- Build tooling: Android Gradle Plugin 8.7.3, Gradle 8.11.1 (wrapper), Kotlin 2.1.0 (with the Kotlin Compose compiler plugin 2.1.0). JDK 17 (`sourceCompatibility`/`targetCompatibility`/`jvmTarget` = 17).
- Android SDK: compileSdk 35 / targetSdk 35, minSdk 29 (Android 10+).
- App identity: `applicationId` / `namespace` = `com.tyler.gbcartdumper`, versionName `1.0.3`, versionCode `4`.
- IDE: Android Studio Ladybug or newer (needed for AGP 8.7). You can also build from the command line with an Android SDK 35 install.
- Hardware: an Android device with USB host (OTG) support (`android.hardware.usb.host` is declared `required="true"`). You also need a jrodrigo GB Cart Flasher rev.c (FTDI FT232R, USB VID 0x0403 / PID 0x6001) and a GB/GBC cartridge.
- Accounts/keys: none are needed to build a debug APK. There is no signing config in this repo; release signing happens outside it (see Build & Run).
- Python tools: `pyserial` (`import serial`, `serial.tools.list_ports`).

## Dependencies

App (Gradle, declared in `gradle/libs.versions.toml`):

| Library | Version | License |
|---|---|---|
| androidx.core:core-ktx | 1.15.0 | Apache-2.0 |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 |
| androidx.lifecycle:lifecycle-runtime-compose / lifecycle-viewmodel-compose | 2.8.7 | Apache-2.0 |
| androidx.compose BOM (ui, ui-graphics, ui-tooling(-preview), material3, material-icons-extended) | 2024.12.01 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core / -android | 1.9.0 | Apache-2.0 |
| androidx.documentfile:documentfile | 1.0.1 | Apache-2.0 |
| com.github.mik3y:usb-serial-for-android (via JitPack) | 3.8.1 | MIT |

Build plugins: AGP 8.7.3 (Apache-2.0), Kotlin Android / Kotlin Compose plugins 2.1.0 (Apache-2.0). Gradle Wrapper scripts and jar are bundled in the repo (Apache-2.0, see `THIRD-PARTY.md`).

Repositories: `google()`, `mavenCentral()`, plus `https://jitpack.io`, which is needed for usb-serial-for-android and is restricted to that one group with `content { includeGroup("com.github.mik3y") }` (`settings.gradle.kts`), so nothing else can resolve from JitPack.

Python tools: `pyserial` — BSD-3-Clause.

## Setup Instructions

```bash
# 1. Clone
git clone https://github.com/SexualMoose/GBCartDumper.git
cd GBCartDumper

# 2. Install JDK 17 and Android SDK 35.
#    Android Studio provisions the SDK on first open. For CLI builds, either set
#    ANDROID_HOME or create local.properties with: sdk.dir=/path/to/Android/sdk
#    (local.properties is gitignored and must NOT be committed.)

# 3. (Optional) Python probe tools
python3 -m pip install pyserial
```

## Build & Run

```bash
# Debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install to a connected device (adb)
./gradlew :app:installDebug
```

Release builds: the `release` build type in `app/build.gradle.kts` has `isMinifyEnabled = false` and **no `signingConfig`**, so an unmodified `./gradlew :app:assembleRelease` produces an unsigned APK. The owner's self-hosted fleet pipeline builds and signs distributed builds outside this repo (commit `5d7f9a9`, "migrate to fleet release key"). This repo does not contain the pipeline's build type or signing details, so they are unverified here.

## Usage

1. Plug the GB Cart Flasher rev.c into the phone via USB-C OTG. The `USB_DEVICE_ATTACHED` filter (`res/xml/device_filter.xml`, VID 1027 / PID 24577 = 0x0403/0x6001) can launch the app on plug-in. The device card shows "Flasher attached" and the FT232R VID:PID. Once USB permission is granted, it also shows the serial number. **Rescan** re-checks presence.
2. Grant USB permission when prompted. The app requests it the first time you tap an action that needs the device. After you grant it, the app automatically runs **Scan cart**.
3. (Recommended on a new flasher) Tap **Auto-detect baud + MBC**. It tries your currently selected baud first, then the rest of 375000 / 187500 / 185000 / 125000 (fastest first). For each baud it tries ROM only, MBC1, MBC5, MBC3, MBC2 and MBC5 Rumble. It reads 32 KiB and accepts the first combination whose bytes at 0x0104 match the 48-byte Nintendo boot logo. The winning combination is saved for that flasher's FT232R serial number and reused next time. Starting auto-detect first erases any saved entry for that flasher, and a failed sweep saves nothing (see Known Issues). **Scan cart** does not auto-detect.
4. Or set **Baud rate** and **MBC** manually in the Settings card. The app starts at 375000 baud and "ROM only".
5. Tap **Choose** to pick a destination folder (SAF tree picker). The app takes a persistable URI permission, but it does not remember the folder across app launches, so you choose it again each session.
6. Tap **Scan cart**. The app reads the first 32 KiB (2 banks) at the selected baud. It tries the selected MBC first, then ROM only → MBC1 → MBC3 → MBC5. It then parses the header: title (with a Shift-JIS fallback for Japanese titles), CGB flag, cart type, ROM/RAM size, header checksum and a logo check. It also shows whether this firmware supports the cart's mapper. This version does not send the flasher's STATUS command.
7. Tap **Dump ROM**. The button is enabled once a cart has been scanned and a folder chosen. The whole ROM streams with a progress bar, and you can cancel it with the ✕ button. After the dump the app checks the global checksum and the MBC1-multicart heuristic, then saves the file. Either check failing only logs a warning; the file is saved anyway. It is saved as `<Title>-yyyyMMdd-HHmmss.gb` or `.gbc`. Characters outside `A–Z a–z 0–9 . _ space -` in the title become `_`. If the folder already holds a `.gb`/`.gbc` whose name starts with the same title, a dialog says whether the contents are identical (same size + SHA-256) and offers **Save as new**, **Overwrite** or **Cancel**.
8. The **Log** card keeps the last 200 timestamped lines. Use the buttons to copy it to the clipboard or clear it.

Python probe tools (on a PC, for debugging a flasher). If you omit the port, each script picks the first FTDI 0403:6001 port it finds:
```bash
python3 tools/probe_pc.py [PORT] [BAUD]             # send STATUS, print + decode the 72-byte reply (default 185000)
python3 tools/probe_pc_rrom.py [PORT] [BAUD] [MBC]  # CONFIG+RROM for 2 banks, print received frames (default 185000, MBC 0x04 ROM-only)
python3 tools/sweep_pc.py [PORT]                    # baud x MBC matrix, incl. off-spec 460800/500000/750000 as negative controls
```

## Architecture

App entry: `GBCartDumperApp.kt` (the Application class, registered with `android:name` in the manifest) installs `fleet/FleetReporter.kt`; see Crash and error reports (FleetReporter).

UI layer (Jetpack Compose, single-activity MVVM):
- `MainActivity.kt` — hosts the Compose UI and keeps the screen on. It handles the USB-attach intent (`singleTask`), requests USB permission through a package-scoped `PendingIntent`, and registers runtime receivers for the permission result and for USB attach/detach. It also takes the persistable SAF folder permission.
- `ui/DumperScreen.kt` — the single screen: device-status card, auto-detect button, Settings card (baud dropdown, MBC dropdown, save-folder picker), cart card, progress card, Scan / Dump / Cancel row, last-dump card, log card (copy/clear), and the duplicate-ROM dialog.
- `ui/DumperViewModel.kt` — UI state (`StateFlow`) and the auto-detect sweep. It also runs scan and dump on coroutines, chooses the MBC for a dump (manual override → MBC that worked during scan/auto-detect → header MBC → ROM only), runs the post-dump integrity checks and duplicate detection, and writes files through SAF `DocumentFile`.
- `ui/Theme.kt`, `ui/AccentState.kt` — Material 3 theme with an OLED-black dark scheme (follows system dark mode; a light scheme exists). The accent hue is random on each cold start and re-randomizes on every button tap.

Flasher protocol layer (`flasher/`):
- `Protocol.kt` — constants: 72-byte packets, 64-byte data frames, 16 KiB pages (256 frames). Markers `DATA=0x55`, `ACK=0xAA`, `NAK=0xF0`, `END=0x0F`. Sub-commands (packet[1]) `CONFIG=0x00`, `DATA_NORMAL=0x01`, `DATA_LAST=0x02`, `ERASE=0x03`, `STATUS=0x04`. Operations (packet[2]) `RROM=0x00`, `RRAM=0x01`, `WROM=0x02`, `WRAM=0x03`. MBC codes `AUTO=0`, `MBC1=1`, `MBC2=2`, `MBC3=3`, `ROMONLY=4`, `MBC5=5`, `RUMBLE=6`. Flash algorithm codes, plus header-cart-type → MBC mapping.
- `Crc16.kt` — table-driven CRC-16 (poly 0x1021, init 0x0000), stored big-endian in packet bytes [70..71].
- `FtdiTransport.kt` — thin wrapper over usb-serial-for-android's FT232 driver. It sets 8N1, never asserts DTR/RTS (they can hold the ATmega in reset), and sets a 2 ms latency timer via reflection. It reads through a carry-over staging buffer, which the FT232 driver needs.
- `GBFlasher.kt` — `readRom()` builds the CONFIG+RROM packet (`[0x55, 0x00, 0x00 RROM, mbc, alg, 0x00, (pages−1)_hi, (pages−1)_lo, 0…, crc_hi, crc_lo]`). The device's first DATA packet doubles as acceptance; there is no separate ACK. Data packets carry the packet number in [3], the page in [4..5] and the 64-byte frame in [6..69]. The host ACKs each good frame and NAKs bad CRCs. It handles re-sent frames, stops on `DATA_LAST`, and recovers a wedged firmware (END + 400 ms + flush). Bank count is limited to 1..512. `readStatus()` (STATUS command) is implemented but **unused**: commit `4f0e412` dropped it from the scan flow because it wedges the firmware on MASKROM carts.
- `CartHeader.kt` — parses the header at 0x0100–0x014F (title, CGB flag, cart type, ROM/RAM banks, header checksum, global checksum). It holds the 48-byte boot-logo comparison constant and classifies mapper support. It also computes the global checksum and runs the MBC1-multicart heuristic (a second logo at 0x40104).

Util: `util/FlasherPrefs.kt` — private SharedPreferences `flasher_prefs` holding the last-known-good baud + MBC per FTDI serial number.

Data flow: USB attach/detach broadcast or Rescan → `FtdiTransport.findDevice` → serial lookup → `FlasherPrefs.load` → (auto-detect: baud × MBC sweep via `GBFlasher.readRom(2 banks)` + logo check → `FlasherPrefs.save`) → Scan: `readRom(2 banks)` with MBC fallback → `CartHeader.parse` → Dump: `readRom(all banks)` → checksum/multicart checks → duplicate check → `DocumentFile` write.

PC tools (`tools/`): `probe_pc.py`, `probe_pc_rrom.py`, `sweep_pc.py` — standalone pyserial scripts with their own copies of the CRC and packet builders. They do not interact with the app.

## Integrations & Interconnects

- Hardware: jrodrigo GB Cart Flasher rev.c (ATmega8515 MCU + FTDI FT232RL USB-to-serial bridge). This is the only external device the app talks to, over local USB serial with no network.
- FleetGuardian (same phone): `fleet/FleetReporter.kt` sends crash, freeze, error and warning reports to FleetGuardian's health provider (`content://com.sexualmoose.fleetguardian.health`, `report_issue`; no network). See Crash and error reports (FleetReporter).
- Library: mik3y/usb-serial-for-android (from GitHub via JitPack) provides the FT232R USB-serial driver.
- Owner's fleet tooling: distributed builds are produced and signed outside this repo by the owner's self-hosted fleet pipeline (details live outside this repo and are unverified here). Apart from FleetReporter, the app contains **no** fleet client code: no fleet app key, no self-update, no network calls.
- Protocol knowledge comes from the original jrodrigo rev.c firmware, Nold360/gbcflsh (Qt host) and Tauwasser/GBCartFlasher (firmware rewrite), all credited in the README. Header layout follows the Pan Docs cartridge-header reference cited in `CartHeader.kt`.
- No cloud services, APIs or analytics; crash and error reports go only to FleetGuardian on the same phone. The app requests no permissions at all; the manifest declares only the USB-host feature and a `<queries>` entry for FleetGuardian's provider.

## Crash and error reports (FleetReporter)

- **File:** `app/src/main/kotlin/com/tyler/gbcartdumper/fleet/FleetReporter.kt` (a copy of FleetGuardian's `docs/fleet-reporter/FleetReporter.kt`), installed in `GBCartDumperApp.onCreate()`. `GBCartDumperApp` was added for this and is registered on `<application>`; the manifest's `<queries>` entry lets the app see FleetGuardian's provider.
- **What it reports:** crashes, freezes (ANR: main thread blocked over 5 s) and errors/warnings the app logs on purpose (`FleetReporter.error()` / `warn()`; the app has no call sites yet) to FleetGuardian on the same phone, over its content provider: no network and no permission.
- **Where they go:** FleetGuardian forwards them to the Jarvis server's Reports, and a fatal one (crash, ANR, crash loop, failing to start) can start an automatic fix.
- **Privacy:** text is redacted (emails, tokens, secrets such as `password=` values, long hex/base64 runs) before it leaves the app. Only the app's own logcat lines are included; no ROM data, files or device ids are sent.
- **Without FleetGuardian 0.3.0+ on the phone:** reports wait in a queue of at most 50 (`filesDir/fleetreporter/queue`) and are resent at the next start; nothing else changes.
- Spec: FleetGuardian `docs/fleet-reporter/README.md` (dev hub contract section 6).

## Configuration & Secrets

- The code reads no secrets, environment variables, BuildConfig fields or custom `local.properties` keys. The only local file needed is the standard `local.properties` (`sdk.dir`) for CLI builds, and it is gitignored.
- No `.env`, `google-services.json`, keystore or service-account file is in the tree, and none appears in the history.
- Release signing material belongs to the external fleet pipeline. It must never be committed here. (`.gitignore` does not currently list `*.jks` / `*.keystore`.)
- Runtime state the app persists:
  - SharedPreferences `flasher_prefs` (MODE_PRIVATE): `<serial>_baud`, `<serial>_mbc`, `<serial>_when` — non-sensitive.
  - A persistable SAF read/write grant for the folder you picked. The folder URI itself is not saved.
  - ROM dumps are written only to that user-picked folder.
- Python tools: configured only by command-line arguments (port, baud, MBC).

## Testing

- No automated tests: there is no `app/src/test` or `app/src/androidTest`, no test dependencies and no CI workflow (`.github/` is absent).
- Testing is manual, with the hardware in the loop. The Nintendo-logo comparison in `CartHeader.hasValidNintendoLogo` acts as a self-check that the baud and MBC are right and the bytes are real cart data. After a dump, the global checksum check flags likely-corrupted dumps in the log (a warning only; the file is still saved).
- The `tools/*.py` probe scripts are manual protocol-validation harnesses for a PC with the flasher attached.

## Known Issues / TODO

- Save-RAM (SRAM) dumping/restoring, flash writing and erase are not implemented. Only ROM read is wired up, although `Protocol.kt` defines RRAM/WRAM/WROM/ERASE.
- MBC3 RTC register state is not captured (`MbcSupport.SupportedRomOnly`).
- Unsupported mappers: MMM01 multicart, MBC6, MBC7, Pocket Camera, TAMA5, HuC3, HuC1. A scan shows a warning on the cart card and in the log, but **Dump ROM** is not blocked.
- MBC1 multicarts may alias sub-games together because the rev.c firmware doesn't mask bank 4 correctly. The app logs a warning after the dump.
- The global-checksum check has a known false positive (Bomberman Quest, per the in-app message).
- The save folder is not remembered across app launches (the URI grant is persisted but the URI is not stored).
- After a USB permission grant, the app always re-runs **Scan cart**, whichever action triggered the request.
- Running auto-detect erases the flasher's saved baud/MBC before sweeping; if the sweep fails, the old entry is lost.
- The new-flasher log line tells the user to tap **Scan cart** to auto-detect; the user actually needs to tap **Auto-detect baud + MBC**.
- A global-checksum mismatch or a suspected MBC1 multicart only logs a warning; the dump is saved anyway.
- `GBFlasher.readStatus()` is dead code since STATUS was removed from the scan flow.
- Unused resources: `app/src/main/res/drawable-nodpi/splash.png` (~1.9 MB, unreferenced, added by the icon-regeneration commit) is still packaged in the APK because resource shrinking is off. `app/src/main/res/drawable/ic_launcher_foreground.xml` (a floppy-disk vector) is also unreferenced. The adaptive icon now uses the `mipmap-*/ic_launcher_foreground.png` rasters.
- Release build has minification disabled and no signing config; it relies on the external fleet pipeline.
- No tests and no CI.

## Third-party & Licensing

- **License: none chosen yet.** There is no LICENSE file, so all rights are reserved by default even though the repository is public. The owner needs to choose a license if redistribution is intended.
- All dependencies are permissive (Apache-2.0 / MIT / BSD-3-Clause). No GPL/LGPL/AGPL/MPL library is declared. The build excludes `/META-INF/{AL2.0,LGPL2.1}` resources from the APK; this is the standard Android Studio Compose template exclusion, and which dependency ships those files is unverified.
- Bundled third-party files: the Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, Apache-2.0). No upstream source trees are vendored. See `THIRD-PARTY.md`.
- Protocol re-implementation: the Kotlin and Python code re-implement the rev.c wire protocol. Code comments say the constant values come from the original firmware / gbcflsh `const.h`. No upstream source files are in the repo. The upstream projects' licenses are unverified.
- **Nintendo IP flags:**
  - The 48-byte Nintendo boot-logo byte sequence is embedded literally in `flasher/CartHeader.kt` and `tools/sweep_pc.py`. It is used only as a comparison oracle against bytes read from the user's own cartridge and is never displayed. Nintendo claims copyright/trademark rights in this logo. Embedding it is common practice in GB tooling, but it is third-party material in a public repo. A hash comparison would avoid shipping the bytes.
  - "Game Boy" / "Game Boy Color" are Nintendo trademarks, used descriptively in the docs. The app's identity uses the "GB" abbreviation ("GB Cart Dumper", package `com.tyler.gbcartdumper`). This is trademark-in-identity, low risk as nominative use; avoid implying Nintendo affiliation. The launcher icon is a higher-risk element (see the Launcher icon note below).
- Other names: "GB Cart Flasher rev.c" / jrodrigo, Nold360, Tauwasser refer to the hardware/firmware/host projects this app interoperates with and are credited. FTDI / FT232R are FTDI Ltd. names, used descriptively.
- Launcher icon: AI-generated by the owner (commit `71ee554`). It is not copied third-party art, but see this trade-dress note. The `mipmap-*/ic_launcher*.png` rasters used by the adaptive icon depict a handheld that closely resembles Nintendo's original Game Boy (DMG-01) design: a grey body, screen bezel, D-pad, A/B buttons and a cartridge-style corner. That evokes Nintendo trade dress in the app's identity, beyond the "GB" name abbreviation above. It is a trademark/trade-dress-in-identity risk for a public app. Consider a non-console, non-derivative icon, for example the unused floppy vector in `res/drawable/ic_launcher_foreground.xml`. The unreferenced `splash.png` from the same commit still ships in the APK because resource shrinking is off.

## Security notes

- Trust boundary: the only external peer is the USB-attached FT232R flasher. Bytes from it are treated as untrusted: every packet is CRC-checked, and the bank count is bounded before allocation. There is no network access at all: no INTERNET permission, no HTTP or socket code, no analytics. On the phone itself, FleetReporter hands redacted crash, freeze and error reports to FleetGuardian over its content provider; FleetGuardian forwards them to the Jarvis server.
- Auth model: no accounts. Access to the flasher is gated by Android's per-device USB permission prompt. File output is gated by the folder the user explicitly picks through SAF.
- Inbound surfaces: the exported launcher activity (also the target of the FT232R USB-attach filter); a runtime receiver for the USB-permission result (not exported, `RECEIVER_NOT_EXPORTED`), which reads only the granted flag and then refreshes and scans; and an exported runtime receiver (`RECEIVER_EXPORTED`) for the system `USB_DEVICE_ATTACHED`/`USB_DEVICE_DETACHED` broadcasts. Those are protected broadcasts, and that receiver only calls `refreshDevicePresence()`.
- Data at rest: only non-sensitive baud/MBC preferences in private SharedPreferences; `allowBackup="false"`.
- Secrets: none in the repo or its history. Release signing keys live only with the external fleet pipeline.
- Known risk categories, high level: release-build hardening (minification off), build provenance and signing handled outside this repo, and third-party IP in a public repo.
