package com.tyler.gbcartdumper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.tyler.gbcartdumper.flasher.Protocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumperScreen(
    viewModel: DumperViewModel = viewModel(),
    onNeedUsbPermission: (android.hardware.usb.UsbDevice) -> Unit,
    onFolderPicked: (Uri) -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = LocalAccentState.current

    /** Run [block] after nudging the accent to a fresh random hue. */
    fun shuffled(block: () -> Unit): () -> Unit = {
        accent.shuffle()
        block()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            accent.shuffle()
            onFolderPicked(uri)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "GB Cart Dumper",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DeviceStatusCard(
                connected = state.deviceConnected,
                label = state.deviceLabel,
                onRefresh = shuffled(viewModel::refreshDevicePresence)
            )

            SettingsCard(
                baud = state.baud,
                mbc = state.mbc,
                saveFolderLabel = state.saveFolderLabel,
                onBaudChange = { accent.shuffle(); viewModel.setBaud(it) },
                onMbcChange = { accent.shuffle(); viewModel.setMbc(it) },
                onPickFolder = shuffled { folderPicker.launch(null) },
            )

            CartCard(cart = state.cart)

            ProgressCard(
                busy = state.busy,
                label = state.busyLabel,
                bytes = state.progressBytes,
                total = state.progressTotal,
            )

            ActionRow(
                busy = state.busy,
                canScan = state.deviceConnected,
                canDump = state.deviceConnected && state.cart != null && state.saveFolder != null,
                autoDetectDone = state.autoDetectDone,
                onScan = shuffled { viewModel.scanCart(onNeedUsbPermission) },
                onDump = shuffled { viewModel.dumpRom(onNeedUsbPermission) },
                onForceAutoDetect = shuffled(viewModel::forceAutoDetect),
                onCancel = shuffled(viewModel::cancel),
            )

            if (state.lastDumpName != null) {
                LastDumpCard(state.lastDumpName!!)
            }

            LogCard(
                lines = state.log,
                onCopy = shuffled {
                    val text = viewModel.logAsText()
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "Log copied (${state.log.size} lines)", Toast.LENGTH_SHORT).show()
                },
                onClear = shuffled(viewModel::clearLog),
            )
        }

        state.duplicatePrompt?.let { prompt ->
            DuplicatePromptDialog(
                prompt = prompt,
                onSaveAsNew = shuffled(viewModel::confirmDuplicateSaveAsNew),
                onOverwrite = shuffled(viewModel::confirmDuplicateOverwrite),
                onDismiss = shuffled(viewModel::dismissDuplicatePrompt),
            )
        }
    }
}

@Composable
private fun DuplicatePromptDialog(
    prompt: DumperViewModel.UiState.DuplicatePrompt,
    onSaveAsNew: () -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (prompt.identicalContent) "Identical ROM already saved" else "Similar ROM already in folder") },
        text = {
            Column {
                Text("Existing:")
                Text(prompt.existingName, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(8.dp))
                Text("New dump will be saved as:")
                Text(prompt.proposedName, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (prompt.identicalContent)
                        "Contents are byte-for-byte identical. Saving again will just leave a duplicate timestamped copy."
                    else
                        "Contents differ from the existing file. Overwriting will delete the older dump.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveAsNew) { Text("Save as new") }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onOverwrite) { Text("Overwrite") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DeviceStatusCard(connected: Boolean, label: String, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) MaterialTheme.colorScheme.primary else Color(0xFF555B66))
            )
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "Flasher attached" else "No flasher",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onRefresh) { Text("Rescan") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(
    baud: Int,
    mbc: Protocol.Mbc,
    saveFolderLabel: String,
    onBaudChange: (Int) -> Unit,
    onMbcChange: (Protocol.Mbc) -> Unit,
    onPickFolder: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            Text("Baud rate (match rev.c jumper)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(125_000, 185_000, 187_500, 375_000).forEach { option ->
                    FilterChip(
                        selected = baud == option,
                        onClick = { onBaudChange(option) },
                        label = { Text(option.toString()) }
                    )
                }
            }

            var mbcMenuOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = mbcMenuOpen,
                onExpandedChange = { mbcMenuOpen = it },
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = mbc.label,
                    onValueChange = {},
                    label = { Text("MBC (memory controller)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mbcMenuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = mbcMenuOpen,
                    onDismissRequest = { mbcMenuOpen = false },
                ) {
                    Protocol.Mbc.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            onClick = {
                                onMbcChange(entry)
                                mbcMenuOpen = false
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Save folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        saveFolderLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalButton(onClick = onPickFolder) { Text("Choose") }
            }
        }
    }
}

@Composable
private fun CartCard(cart: DumperViewModel.UiState.CartInfo?) {
    AnimatedVisibility(
        visible = cart != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val c = cart ?: return@AnimatedVisibility
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(c.title, style = MaterialTheme.typography.titleLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(".${c.extension}") },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.primary)
                    )
                    AssistChip(onClick = {}, label = { Text(c.mbc.label) })
                    AssistChip(onClick = {}, label = { Text("${c.romBytes / 1024} KiB") })
                    if (c.ramBytes > 0) AssistChip(onClick = {}, label = { Text("RAM ${c.ramBytes / 1024} KiB") })
                }
                Text(
                    "Type ${c.cartTypeHex} · header ${if (c.headerOk) "OK" else "BAD"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!c.supportStatus.usable) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            c.supportMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(busy: Boolean, label: String, bytes: Long, total: Long) {
    val showing = busy || (total > 0 && bytes in 1..<total)
    AnimatedVisibility(showing, enter = fadeIn(), exit = fadeOut()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (label.isNotBlank()) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
                val frac = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        "${bytes / 1024} / ${total / 1024} KiB  (${(frac * 100).toInt()} %)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    busy: Boolean,
    canScan: Boolean,
    canDump: Boolean,
    autoDetectDone: Boolean,
    onScan: () -> Unit,
    onDump: () -> Unit,
    onForceAutoDetect: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onScan,
                enabled = !busy && canScan,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan cart")
            }
            Button(
                onClick = onDump,
                enabled = !busy && canDump,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Dump ROM")
            }
            if (busy) {
                FilledTonalButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        }
        OutlinedButton(
            onClick = onForceAutoDetect,
            enabled = !busy && canScan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (autoDetectDone) "Re-run auto-detect" else "Auto-detect on next scan")
        }
    }
}

@Composable
private fun LastDumpCard(filename: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Last dump saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(filename, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun LogCard(lines: List<String>, onCopy: () -> Unit, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onCopy, enabled = lines.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy log",
                        tint = if (lines.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClear, enabled = lines.isNotEmpty()) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "Clear log",
                        tint = if (lines.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                color = Color.Transparent,
                modifier = Modifier.heightIn(min = 80.dp, max = 260.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (lines.isEmpty()) {
                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
