package com.tyler.gbcartdumper.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyler.gbcartdumper.flasher.CartHeader
import com.tyler.gbcartdumper.flasher.FtdiTransport
import com.tyler.gbcartdumper.flasher.GBFlasher
import com.tyler.gbcartdumper.flasher.Protocol
import com.tyler.gbcartdumper.util.FlasherPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class DumperViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val deviceConnected: Boolean = false,
        val deviceLabel: String = "No flasher detected",
        val deviceSerial: String = "",
        val baud: Int = 185_000,
        val mbc: Protocol.Mbc = Protocol.Mbc.Auto,
        val userOverrodeBaud: Boolean = false,
        val userOverrodeMbc: Boolean = false,
        val autoDetectDone: Boolean = false,
        val saveFolder: Uri? = null,
        val saveFolderLabel: String = "No folder chosen",
        val cart: CartInfo? = null,
        val workingMbc: Protocol.Mbc? = null,
        val busy: Boolean = false,
        val busyLabel: String = "",
        val progressBytes: Long = 0,
        val progressTotal: Long = 0,
        val log: List<String> = emptyList(),
        val lastDumpUri: Uri? = null,
        val lastDumpName: String? = null,
        val duplicatePrompt: DuplicatePrompt? = null,
    ) {
        data class CartInfo(
            val title: String,
            val extension: String,
            val mbc: Protocol.Mbc,
            val romBytes: Int,
            val ramBytes: Int,
            val cartTypeHex: String,
            val headerOk: Boolean,
            val supportStatus: CartHeader.MbcSupport,
            val supportMessage: String,
        )

        /** State for the "ROM already exists" AlertDialog. */
        data class DuplicatePrompt(
            val existingName: String,
            val proposedName: String,
            val identicalContent: Boolean,
        )
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val prefs = FlasherPrefs(app)

    private var currentJob: Job? = null

    // The dump bytes + target folder that are waiting on the user's
    // duplicate-file dialog decision.
    private data class PendingDump(val folder: Uri, val filename: String, val rom: ByteArray)
    private var pending: PendingDump? = null

    fun setBaud(baud: Int) = _ui.update { it.copy(baud = baud, userOverrodeBaud = true) }
    fun setMbc(mbc: Protocol.Mbc) = _ui.update { it.copy(mbc = mbc, userOverrodeMbc = true) }
    fun setSaveFolder(uri: Uri?, label: String) =
        _ui.update { it.copy(saveFolder = uri, saveFolderLabel = label) }

    fun refreshDevicePresence() {
        val ctx = getApplication<Application>()
        val dev = FtdiTransport.findDevice(ctx)
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val serial = dev?.let {
            if (usb.hasPermission(it)) runCatching { it.serialNumber ?: "" }.getOrDefault("") else ""
        } ?: ""
        val remembered = if (serial.isNotEmpty()) prefs.load(serial) else null
        val changedFlasher = serial.isNotEmpty() && serial != _ui.value.deviceSerial

        _ui.update {
            var baud = it.baud
            var mbc = it.mbc
            var overrodeBaud = it.userOverrodeBaud
            var overrodeMbc = it.userOverrodeMbc
            var autoDone = it.autoDetectDone
            var workingMbc = it.workingMbc

            if (changedFlasher) {
                // A different flasher (or our first detection of this one) resets
                // overrides so the next Scan will auto-detect from scratch.
                overrodeBaud = false
                overrodeMbc = false
                autoDone = false
                workingMbc = null
                if (remembered != null) {
                    baud = remembered.baud
                    mbc = remembered.mbc
                    autoDone = true   // already learned this serial once
                }
            }

            it.copy(
                deviceConnected = dev != null,
                deviceLabel = dev?.let { d ->
                    val id = "FT232R ${"%04X".format(d.vendorId)}:${"%04X".format(d.productId)}"
                    if (serial.isNotEmpty()) "$id · $serial" else id
                } ?: "No flasher detected",
                deviceSerial = serial,
                baud = baud,
                mbc = mbc,
                userOverrodeBaud = overrodeBaud,
                userOverrodeMbc = overrodeMbc,
                autoDetectDone = autoDone,
                workingMbc = workingMbc,
            )
        }
        if (changedFlasher && remembered == null) {
            appendLog("New flasher detected (serial=$serial). Hit Scan cart to auto-detect baud + MBC.")
        } else if (changedFlasher && remembered != null) {
            appendLog("Recognised flasher $serial — reusing saved baud=${remembered.baud}, MBC=${remembered.mbc.label}.")
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _ui.update { it.copy(busy = false, busyLabel = "") }
    }

    fun clearLog() = _ui.update { it.copy(log = emptyList()) }
    fun logAsText(): String = _ui.value.log.joinToString("\n")

    /** Wipe the saved settings for the currently-attached flasher and re-run auto-detect. */
    fun forceAutoDetect() {
        val serial = _ui.value.deviceSerial
        if (serial.isNotEmpty()) prefs.forget(serial)
        _ui.update {
            it.copy(
                autoDetectDone = false,
                userOverrodeBaud = false,
                userOverrodeMbc = false,
                workingMbc = null,
                cart = null,
            )
        }
        appendLog("Cleared saved flasher settings — next Scan will retry every baud × MBC combo.")
    }

    fun dismissDuplicatePrompt() {
        pending = null
        _ui.update { it.copy(duplicatePrompt = null) }
        appendLog("Dump cancelled — existing file kept.")
    }

    fun confirmDuplicateSaveAsNew() = resolvePendingDump(overwrite = false)
    fun confirmDuplicateOverwrite() = resolvePendingDump(overwrite = true)

    fun scanCart(onNeedPermission: (UsbDevice) -> Unit) {
        val ctx = getApplication<Application>()
        val device = FtdiTransport.findDevice(ctx) ?: run {
            appendLog("No FT232R attached — plug the flasher into the phone's USB-C port.")
            return
        }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            onNeedPermission(device)
            return
        }
        currentJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true, busyLabel = "Scanning cart", cart = null) }
            try {
                val bank0 = acquireBank0(ctx, device)
                val header = CartHeader.parse(bank0)
                val titleForFile = chooseTitle(header)
                val support = header.mbcSupportStatus
                val info = UiState.CartInfo(
                    title = titleForFile.ifBlank { "(untitled)" },
                    extension = header.extension,
                    mbc = header.mbc,
                    romBytes = header.romBytes,
                    ramBytes = header.ramBytes,
                    cartTypeHex = "0x%02X".format(header.cartridgeType),
                    headerOk = header.headerChecksumOk,
                    supportStatus = support,
                    supportMessage = support.label,
                )
                _ui.update { it.copy(cart = info) }
                val logoStatus = if (CartHeader.hasValidNintendoLogo(bank0)) "logo OK" else "LOGO MISSING"
                appendLog("Header: ${info.title} · ${info.mbc.label} · ${info.romBytes / 1024} KiB · checksum ${if (header.headerChecksumOk) "OK" else "BAD"} · $logoStatus")
                if (!support.usable) {
                    appendLog("⚠ ${support.label}. This flasher firmware cannot dump this cart correctly.")
                } else if (support == CartHeader.MbcSupport.SupportedRomOnly) {
                    appendLog("Note: MBC3 cart — ROM dumps fine, but RTC register state isn't captured.")
                }
            } catch (t: Throwable) {
                appendLog("Scan failed: ${t.message}")
            } finally {
                _ui.update { it.copy(busy = false, busyLabel = "", progressBytes = 0, progressTotal = 0) }
            }
        }
    }

    fun dumpRom(onNeedPermission: (UsbDevice) -> Unit) {
        val ctx = getApplication<Application>()
        val info = _ui.value.cart ?: run {
            appendLog("Scan a cart first.")
            return
        }
        val folder = _ui.value.saveFolder ?: run {
            appendLog("Pick a destination folder first.")
            return
        }
        val device = FtdiTransport.findDevice(ctx) ?: run {
            appendLog("No FT232R attached.")
            return
        }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            onNeedPermission(device)
            return
        }

        val mbcToUse = when {
            _ui.value.userOverrodeMbc -> _ui.value.mbc
            _ui.value.workingMbc != null -> _ui.value.workingMbc!!
            info.mbc != Protocol.Mbc.Auto -> info.mbc
            else -> Protocol.Mbc.RomOnly
        }
        val banks = info.romBytes / Protocol.PAGE_SIZE
        val baud = _ui.value.baud

        currentJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true, busyLabel = "Dumping ROM", progressBytes = 0, progressTotal = info.romBytes.toLong()) }
            try {
                val rom = FtdiTransport.open(ctx, device).use { io ->
                    io.configure(baud)
                    val flasher = GBFlasher(io, ::appendLog)
                    appendLog("Dumping $banks banks (${info.romBytes / 1024} KiB) @ $baud using ${mbcToUse.label}...")
                    flasher.readRom(mbcToUse.code, banks) { read, total ->
                        _ui.update { it.copy(progressBytes = read.toLong(), progressTotal = total.toLong()) }
                    }
                }
                // Post-dump integrity checks.
                val wantChecksum = ((rom[0x014E].toInt() and 0xFF) shl 8) or (rom[0x014F].toInt() and 0xFF)
                val gotChecksum = CartHeader.computeGlobalChecksum(rom)
                if (wantChecksum == gotChecksum) {
                    appendLog("Global checksum OK (0x${"%04X".format(gotChecksum)}).")
                } else {
                    appendLog("⚠ Global checksum mismatch — header wants 0x${"%04X".format(wantChecksum)}, computed 0x${"%04X".format(gotChecksum)}. Dump may be corrupted (known false positive: Bomberman Quest).")
                }
                if (info.mbc == Protocol.Mbc.Mbc1 && CartHeader.looksLikeMbc1Multicart(rom)) {
                    appendLog("⚠ MBC1 multicart detected (second Nintendo logo at 0x40104). rev.c firmware doesn't mask bank-4 correctly — the dump may alias sub-games together. Consider a flasher with dedicated multicart support.")
                }
                persistDumpChecked(folder, info, rom)
            } catch (t: Throwable) {
                appendLog("Dump failed: ${t.message}")
            } finally {
                _ui.update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    // --- auto-detect --------------------------------------------------------

    /**
     * Yield bank 0 (32 KiB). If the app already has known-good settings for this
     * flasher, opens once at that baud + MBC. Otherwise sweeps every baud × MBC
     * combination until the Nintendo logo at 0x0104 appears verbatim, then saves
     * the winning pair to prefs so next time we skip the sweep.
     */
    private suspend fun acquireBank0(ctx: Context, device: UsbDevice): ByteArray {
        val state = _ui.value

        // Fast path: trust the user's manual choices or a remembered combo.
        if (state.userOverrodeBaud || state.userOverrodeMbc || state.autoDetectDone) {
            appendLog("Reading bank 0 @ ${state.baud} baud · ${state.mbc.label} ...")
            return FtdiTransport.open(ctx, device).use { io ->
                io.configure(state.baud)
                val flasher = GBFlasher(io, ::appendLog)
                readBank0WithMbcFallback(flasher, state.mbc)
            }
        }

        // Slow path: sweep everything.
        appendLog("Auto-detecting flasher settings — sweeping bauds × MBCs...")
        val bauds = listOf(185_000, 187_500, 125_000, 375_000)
        val mbcs = listOf(
            Protocol.Mbc.RomOnly, Protocol.Mbc.Mbc1, Protocol.Mbc.Mbc5,
            Protocol.Mbc.Mbc3, Protocol.Mbc.Mbc2, Protocol.Mbc.Rumble,
        )
        for (baud in bauds) {
            _ui.update { it.copy(busyLabel = "Auto-detect $baud baud") }
            try {
                FtdiTransport.open(ctx, device).use { io ->
                    io.configure(baud)
                    val flasher = GBFlasher(io, ::appendLog)
                    for (mbc in mbcs) {
                        try {
                            appendLog("  Try $baud baud · ${mbc.label} ...")
                            val bank0 = flasher.readRom(mbc.code, romBanks = 2) { _, _ -> }
                            if (CartHeader.hasValidNintendoLogo(bank0)) {
                                appendLog("  ✓ $baud baud · ${mbc.label} — Nintendo logo verified.")
                                _ui.update {
                                    it.copy(
                                        baud = baud,
                                        mbc = mbc,
                                        workingMbc = mbc,
                                        autoDetectDone = true,
                                    )
                                }
                                val serial = _ui.value.deviceSerial
                                if (serial.isNotEmpty()) prefs.save(serial, baud, mbc)
                                return bank0
                            } else {
                                appendLog("  · $baud · ${mbc.label}: data received but logo mismatch.")
                            }
                        } catch (t: Throwable) {
                            // CONFIG NAK or CRC fail — move on.
                        }
                    }
                }
            } catch (t: Throwable) {
                appendLog("  · $baud baud: port open failed (${t.message})")
            }
        }
        throw IllegalStateException("Auto-detect failed — no combo produced a valid Nintendo logo. Cart may be damaged or not seated.")
    }

    /** Try user-selected MBC first, then the usual fallback chain. */
    private suspend fun readBank0WithMbcFallback(flasher: GBFlasher, preferred: Protocol.Mbc): ByteArray {
        val attempts = buildList {
            add(preferred)
            for (m in listOf(Protocol.Mbc.RomOnly, Protocol.Mbc.Mbc1, Protocol.Mbc.Mbc3, Protocol.Mbc.Mbc5)) {
                if (m !in this) add(m)
            }
        }
        var lastError: Throwable? = null
        for (mbc in attempts) {
            try {
                val bytes = flasher.readRom(mbc.code, romBanks = 2) { read, total ->
                    _ui.update { it.copy(progressBytes = read.toLong(), progressTotal = total.toLong()) }
                }
                _ui.update { it.copy(workingMbc = mbc) }
                return bytes
            } catch (t: Throwable) {
                lastError = t
                appendLog("  MBC=${mbc.label} failed: ${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("No MBC worked")
    }

    // --- save / duplicate detection -----------------------------------------

    private suspend fun persistDumpChecked(folder: Uri, info: UiState.CartInfo, rom: ByteArray) {
        val filename = buildFilename(info.title, info.extension)
        val match = withContext(Dispatchers.IO) { findExistingRom(folder, info, rom) }
        if (match != null) {
            pending = PendingDump(folder, filename, rom)
            _ui.update {
                it.copy(
                    duplicatePrompt = UiState.DuplicatePrompt(
                        existingName = match.name,
                        proposedName = filename,
                        identicalContent = match.identical,
                    )
                )
            }
            appendLog("⚠ Existing ROM in folder: ${match.name} (${if (match.identical) "identical contents" else "different contents"}). Awaiting user decision.")
            return
        }
        writeNew(folder, filename, rom)
    }

    private fun resolvePendingDump(overwrite: Boolean) {
        val p = pending ?: return
        val promptedName = _ui.value.duplicatePrompt?.existingName
        pending = null
        _ui.update { it.copy(duplicatePrompt = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (overwrite && promptedName != null) {
                    val ctx = getApplication<Application>()
                    val dir = DocumentFile.fromTreeUri(ctx, p.folder)
                    dir?.findFile(promptedName)?.delete()
                    writeNew(p.folder, promptedName, p.rom)
                } else {
                    writeNew(p.folder, p.filename, p.rom)
                }
            }
        }
    }

    private data class ExistingRomMatch(val name: String, val identical: Boolean)

    /** Is there any file in [folderUri] with the same title prefix? If so, is it byte-identical? */
    private fun findExistingRom(folderUri: Uri, info: UiState.CartInfo, rom: ByteArray): ExistingRomMatch? {
        val ctx = getApplication<Application>()
        val dir = DocumentFile.fromTreeUri(ctx, folderUri) ?: return null
        val safeTitle = sanitizeTitle(info.title).lowercase()
        val newHash = sha256(rom)

        for (file in dir.listFiles()) {
            val name = file.name ?: continue
            val lower = name.lowercase()
            val prefixMatches = lower.startsWith(safeTitle) && (lower.endsWith(".gb") || lower.endsWith(".gbc"))
            if (!prefixMatches) continue
            // Compare content if sizes match — cheaper than hashing a 4 MiB file otherwise.
            val identical = if (file.length() == rom.size.toLong()) {
                val existing = runCatching {
                    ctx.contentResolver.openInputStream(file.uri).use { it?.readBytes() ?: ByteArray(0) }
                }.getOrNull() ?: continue
                sha256(existing).contentEquals(newHash)
            } else false
            return ExistingRomMatch(name, identical)
        }
        return null
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun writeNew(folderUri: Uri, filename: String, data: ByteArray) {
        val ctx = getApplication<Application>()
        val dir = DocumentFile.fromTreeUri(ctx, folderUri) ?: return
        dir.findFile(filename)?.delete()
        val file = dir.createFile("application/octet-stream", filename) ?: return
        ctx.contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
        _ui.update { it.copy(lastDumpUri = file.uri, lastDumpName = filename) }
        appendLog("Saved → $filename (${data.size} bytes)")
    }

    /**
     * If the cart's ASCII title is empty but the raw bytes contain something,
     * assume it's a Japanese cart and decode as Shift-JIS. Falls back to a
     * hex signature so we at least get a deterministic filename.
     */
    private fun chooseTitle(header: CartHeader): String {
        if (header.title.isNotBlank()) return header.title
        val raw = header.rawTitleBytes.takeWhile { it.toInt() != 0 }.toByteArray()
        if (raw.isEmpty()) return ""
        runCatching {
            val decoded = String(raw, charset("Shift_JIS")).trim()
            if (decoded.isNotEmpty() && decoded.any { it.code > 0x20 }) return decoded
        }
        return "cart-" + raw.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.take(12)
    }

    private fun sanitizeTitle(title: String): String =
        title.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "rom" }

    private fun buildFilename(title: String, ext: String): String {
        val safe = sanitizeTitle(title)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "$safe-$stamp.$ext"
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _ui.update {
            val next = it.log + "[$ts] $line"
            it.copy(log = if (next.size > 200) next.takeLast(200) else next)
        }
    }
}
