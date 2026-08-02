@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.heard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.packetradio.mobile.model.HeardBeaconPacket

@Composable
fun HeardStationDetailScreen(
    callsign: String,
    onBack: () -> Unit,
    onDelete: () -> Unit = {},
    viewModel: HeardStationDetailViewModel = viewModel(),
) {
    val entry by remember(callsign) { viewModel.entry(callsign) }.collectAsState()
    val beacons by remember(callsign) { viewModel.beacons(callsign) }.collectAsState()
    var alias by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(entry?.userAlias) { alias = entry?.userAlias ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(callsign) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove station")
                    }
                },
            )
        },
    ) { innerPadding ->
        val current = entry
        Column(Modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
            if (current == null) {
                Text("Not found.", style = MaterialTheme.typography.bodyMedium)
                return@Scaffold
            }

            Text(
                if (current.heardDirectly) "Heard directly" else "Only heard via another station's broadcast",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Alias (used when dialing)") },
                placeholder = { current.autoAlias?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = { viewModel.setUserAlias(callsign, alias) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Save alias") }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            ReadOnlyField("Auto-learned alias", current.autoAlias)
            ReadOnlyField("Name", current.name)
            ReadOnlyField("Location", current.location)
            ReadOnlyField("Notes", current.notes)
            ReadOnlyField("Via", current.via.ifBlank { null })
            ReadOnlyField("Last heard", current.lastHeard)
            ReadOnlyField("Heard count", current.heardCount.toString())

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Last 5 BEACON packets", style = MaterialTheme.typography.titleMedium)
            if (beacons.isEmpty()) {
                Text(
                    "None heard yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(beacons, key = { it.id }) { packet -> BeaconRow(packet) }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove $callsign?") },
            text = { Text("This will remove $callsign from the heard stations list. It will re-appear the next time a frame from this station is heard.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(callsign)
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BeaconRow(packet: HeardBeaconPacket) {
    Surface(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(packet.text, style = MaterialTheme.typography.bodyMedium)
            Text(packet.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
