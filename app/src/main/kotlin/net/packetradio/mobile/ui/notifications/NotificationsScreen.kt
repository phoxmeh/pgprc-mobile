@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.packetradio.mobile.model.NotifiedPacket
import net.packetradio.mobile.model.WatchedDestination

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenPacket: (Long) -> Unit,
    viewModel: NotificationsViewModel = viewModel(),
) {
    val watched by viewModel.watchedDestinations.collectAsState()
    val packets by viewModel.notifiedPackets.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Notification settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 12.dp)) {
            Text("Received", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                items(packets, key = { it.id }) { packet ->
                    NotifiedPacketRow(packet, onClick = { onOpenPacket(packet.id) })
                }
            }
        }
    }

    if (showSettings) {
        NotificationSettingsDialog(
            watched = watched,
            onAdd = { dest -> viewModel.addWatchedDestination(dest) },
            onToggle = { id, enabled -> viewModel.setEnabled(id, enabled) },
            onDelete = { id -> viewModel.deleteWatchedDestination(id) },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun NotificationSettingsDialog(
    watched: List<WatchedDestination>,
    onAdd: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newDestination by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification Settings") },
        text = {
            Column {
                Text("Watched destinations", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    OutlinedTextField(
                        value = newDestination,
                        onValueChange = { newDestination = it.uppercase() },
                        label = { Text("Destination, e.g. MAIL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (newDestination.isNotBlank()) {
                            onAdd(newDestination)
                            newDestination = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add destination")
                    }
                }
                if (watched.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 4.dp)) {
                        items(watched, key = { it.id }) { destination ->
                            WatchedDestinationRow(
                                destination = destination,
                                onToggle = { onToggle(destination.id, it) },
                                onDelete = { onDelete(destination.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun WatchedDestinationRow(destination: WatchedDestination, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(destination.destination, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = destination.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${destination.destination}")
        }
    }
}

@Composable
private fun NotifiedPacketRow(packet: NotifiedPacket, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(packet.line.take(120), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(packet.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
