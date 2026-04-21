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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DumperViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val deviceConnected: Boolean = false,
        val deviceLabel: String = "No flasher detected",
        val baud: Int = 185_000,
        val mbc: Protocol.Mbc = Protocol.Mbc.Auto,
        val saveFolder: Uri? = null,
        val saveFolderLabel: String = "No folder chosen",
        val cart: CartInfo? = null,
        val busy: Boolean = false,
        val progressBytes: Long = 0,
        val progressTotal: Long = 0,
        val log: List<String> = emptyList(),
        val lastDumpUri: Uri? = null,
        val lastDumpName: String? = null,
    ) {
        data class CartInfo(
            val title: String,
            val extension: String,
            val mbc: Protocol.Mbc,
            val romBytes: Int,
            val ramBytes: Int,
            val cartTypeHex: String,
            val headerOk: Boolean,
        )
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentJob: Job? = null

    fun setBaud(baud: Int) = _ui.update { it.copy(baud = baud) }
    fun setMbc(mbc: Protocol.Mbc) = _ui.update { it.copy(mbc = mbc) }
    fun setSaveFolder(uri: Uri?, label: String) =
        _ui.update { it.copy(saveFolder = uri, saveFolderLabel = label) }

    fun refreshDevicePresence() {
        val dev = FtdiTransport.findDevice(getApplication())
        _ui.update {
            it.copy(
                deviceConnected = dev != null,
                deviceLabel = dev?.let { d -> "FT232R ${"%04X".format(d.vendorId)}:${"%04X".format(d.productId)}" }
                    ?: "No flasher detected"
            )
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _ui.update { it.copy(busy = false) }
    }

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
            _ui.update { it.copy(busy = true, cart = null) }
            try {
                FtdiTransport.open(ctx, device).use { io ->
                    io.configure(_ui.value.baud)
                    val flasher = GBFlasher(io, ::appendLog)
                    appendLog("Querying cartridge (STATUS)...")
                    val status = flasher.readStatus(_ui.value.mbc.code)
                    appendLog("Flasher replied: \"${status.gameName}\" type=0x${"%02X".format(status.cartType)} ROM=0x${"%02X".format(status.romSizeCode)}")

                    // Grab bank 0 (32 KiB) to parse the real cart header.
                    appendLog("Reading bank 0 to confirm cart header...")
                    val bank0 = flasher.readRom(_ui.value.mbc.code, romBanks = 2) { read, total ->
                        _ui.update { it.copy(progressBytes = read.toLong(), progressTotal = total.toLong()) }
                    }
                    val header = CartHeader.parse(bank0)
                    val info = UiState.CartInfo(
                        title = header.title.ifBlank { "(untitled)" },
                        extension = header.extension,
                        mbc = header.mbc,
                        romBytes = header.romBytes,
                        ramBytes = header.ramBytes,
                        cartTypeHex = "0x%02X".format(header.cartridgeType),
                        headerOk = header.headerChecksumOk,
                    )
                    _ui.update { it.copy(cart = info) }
                    appendLog("Detected: ${info.title} · ${info.mbc.label} · ${info.romBytes / 1024} KiB · header checksum ${if (header.headerChecksumOk) "OK" else "BAD"}")
                }
            } catch (t: Throwable) {
                appendLog("Scan failed: ${t.message}")
            } finally {
                _ui.update { it.copy(busy = false, progressBytes = 0, progressTotal = 0) }
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

        val mbcToUse = if (_ui.value.mbc == Protocol.Mbc.Auto) info.mbc else _ui.value.mbc
        val banks = info.romBytes / Protocol.PAGE_SIZE

        currentJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true, progressBytes = 0, progressTotal = info.romBytes.toLong()) }
            try {
                val rom = FtdiTransport.open(ctx, device).use { io ->
                    io.configure(_ui.value.baud)
                    val flasher = GBFlasher(io, ::appendLog)
                    appendLog("Dumping $banks banks (${info.romBytes / 1024} KiB) using ${mbcToUse.label}...")
                    flasher.readRom(mbcToUse.code, banks) { read, total ->
                        _ui.update { it.copy(progressBytes = read.toLong(), progressTotal = total.toLong()) }
                    }
                }
                val filename = buildFilename(info.title, info.extension)
                val outUri = withContext(Dispatchers.IO) { writeToFolder(folder, filename, rom) }
                _ui.update { it.copy(lastDumpUri = outUri, lastDumpName = filename) }
                appendLog("Saved → $filename (${rom.size} bytes)")
            } catch (t: Throwable) {
                appendLog("Dump failed: ${t.message}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun buildFilename(title: String, ext: String): String {
        val safe = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "rom" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "$safe-$stamp.$ext"
    }

    private fun writeToFolder(folderUri: Uri, filename: String, data: ByteArray): Uri? {
        val ctx = getApplication<Application>()
        val dir = DocumentFile.fromTreeUri(ctx, folderUri) ?: return null
        // Overwrite if a same-named file exists.
        dir.findFile(filename)?.delete()
        val file = dir.createFile("application/octet-stream", filename) ?: return null
        ctx.contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
        return file.uri
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _ui.update {
            val next = it.log + "[$ts] $line"
            it.copy(log = if (next.size > 200) next.takeLast(200) else next)
        }
    }
}
