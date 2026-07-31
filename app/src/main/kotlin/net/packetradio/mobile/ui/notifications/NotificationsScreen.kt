@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    var newDestination by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 12.dp)) {
            Text("Watched destinations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(
                    value = newDestination,
                    onValueChange = { newDestination = it.uppercase() },
                    label = { Text("Destination, e.g. MAIL") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.addWatchedDestination(newDestination); newDestination = "" }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add destination")
                }
            }
            for (destination in watched) {
                WatchedDestinationRow(
                    destination = destination,
                    onToggle = { viewModel.setEnabled(destination.id, it) },
                    onDelete = { viewModel.deleteWatchedDestination(destination.id) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Received", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                items(packets, key = { it.id }) { packet ->
                    NotifiedPacketRow(packet, onClick = { onOpenPacket(packet.id) })
                }
            }
        }
    }
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
