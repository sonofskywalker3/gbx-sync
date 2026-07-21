package com.jeff.gbxsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jeff.gbxsync.library.RomEntry
import com.jeff.gbxsync.serial.ConnectionState
import com.jeff.gbxsync.sync.SyncDecision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: GbxSyncViewModel,
    onPickRomFolder: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("GBX Sync") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(ui, onRetry = viewModel::tryConnect)
            RomFolderCard(ui, onPick = onPickRomFolder, onRescan = viewModel::rescanLibrary)
            CartCard(ui, onDetect = viewModel::detectCart)
            if (ui.cart != null) {
                MatchCard(ui, onSelect = viewModel::selectMatch)
                SyncCard(ui, onSync = viewModel::sync)
            }
            ui.busyLabel?.let { label ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        val p = ui.progress
                        if (p != null) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            ui.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ui.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ConnectionCard(ui: UiState, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Device", style = MaterialTheme.typography.titleMedium)
                val text = when (val c = ui.connection) {
                    is ConnectionState.Connected ->
                        "${c.firmware.pcbName} ${c.firmware.pcbLabel} — firmware ${c.firmware.cfwId}${c.firmware.fwVer}"
                    ConnectionState.PermissionPending -> "Waiting for USB permission…"
                    is ConnectionState.Failed -> "Error: ${c.message}"
                    else -> "No GBxCart RW found — plug it in"
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.connection !is ConnectionState.Connected) {
                OutlinedButton(onClick = onRetry, enabled = !ui.isBusy) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun RomFolderCard(ui: UiState, onPick: () -> Unit, onRescan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ROM folder", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.romFolderUri == null) "Not set"
                    else "${ui.romCount} ROMs indexed",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.romFolderUri != null) {
                OutlinedButton(onClick = onRescan, enabled = !ui.isBusy) { Text("Rescan") }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onPick, enabled = !ui.isBusy) {
                Text(if (ui.romFolderUri == null) "Choose" else "Change")
            }
        }
    }
}

@Composable
private fun CartCard(ui: UiState, onDetect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cartridge", style = MaterialTheme.typography.titleMedium)
                val cart = ui.cart
                if (cart == null) {
                    Text("Insert a cartridge and detect", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(cart.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${cart.systemLabel} · save ${if (cart.saveSizeBytes > 0) "${cart.saveSizeBytes / 1024} KB" else "none"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onDetect,
                enabled = ui.connection is ConnectionState.Connected && !ui.isBusy,
            ) {
                Text(if (ui.cart == null) "Detect" else "Re-detect")
            }
        }
    }
}

@Composable
private fun MatchCard(ui: UiState, onSelect: (RomEntry) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Matched ROM", style = MaterialTheme.typography.titleMedium)
            when {
                ui.romFolderUri == null -> Text("Choose a ROM folder first")
                ui.matches.isEmpty() -> Text("No matching ROM found in your folder")
                else -> ui.matches.forEach { entry ->
                    val selected = entry == ui.selectedMatch
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (entry.saveFileUriString != null) "Has .sav file" else "No .sav file yet",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (ui.matches.size > 1) {
                            OutlinedButton(onClick = { onSelect(entry) }, enabled = !selected && !ui.isBusy) {
                                Text(if (selected) "Selected" else "Select")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(ui: UiState, onSync: (SyncDirection) -> Unit) {
    val cart = ui.cart ?: return
    if (cart.saveSizeBytes == 0) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            val decision = ui.decision
            when (decision) {
                null -> Text("Select a matching ROM to sync")
                SyncDecision.InSync -> Text("✓ Cartridge and save file are identical")
                SyncDecision.NoFileYet -> Text("No .sav file yet — copy the cartridge save to your ROM folder")
                SyncDecision.CartChanged -> Text("Cartridge has newer progress → update the save file")
                SyncDecision.FileChanged -> Text("Save file has newer progress → update the cartridge")
                SyncDecision.NeverSynced -> Text("First sync for this game and both sides differ — choose which one to keep")
                SyncDecision.BothChanged -> Text("⚠ Both the cartridge and the file changed since the last sync — choose which one to keep")
            }
            if (decision != null && decision != SyncDecision.InSync) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (decision) {
                        SyncDecision.NoFileYet, SyncDecision.CartChanged -> {
                            Button(onClick = { onSync(SyncDirection.CART_TO_FILE) }, enabled = !ui.isBusy) {
                                Text("Cart → File")
                            }
                        }
                        SyncDecision.FileChanged -> {
                            Button(onClick = { onSync(SyncDirection.FILE_TO_CART) }, enabled = !ui.isBusy) {
                                Text("File → Cart")
                            }
                        }
                        SyncDecision.NeverSynced, SyncDecision.BothChanged -> {
                            Button(onClick = { onSync(SyncDirection.CART_TO_FILE) }, enabled = !ui.isBusy) {
                                Text("Keep cart save")
                            }
                            Button(onClick = { onSync(SyncDirection.FILE_TO_CART) }, enabled = !ui.isBusy) {
                                Text("Keep file save")
                            }
                        }
                        else -> {}
                    }
                }
                if (decision == SyncDecision.NeverSynced || decision == SyncDecision.BothChanged) {
                    Text(
                        "The replaced copy is backed up inside the app first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
