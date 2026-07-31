@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.AddressBookEntry
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.supportsConnect

/**
 * Dials a new session tab — the only way one is ever created (mirrors the
 * desktop's Ctrl+N dial dialog). Only ports whose kind actually supports a
 * two-way AX.25 connection are offered here — AGWPE, KISS-TCP, and Bluetooth
 * KISS; USB-serial KISS isn't wired up yet and stays unproto-only on the
 * Monitor screen's ad-hoc bar for now (see [net.packetradio.mobile.model.supportsConnect]).
 */
@Composable
fun DialDialog(
    ports: List<PortEntry>,
    heardStations: List<AddressBookEntry> = emptyList(),
    onDismiss: () -> Unit,
    onDial: (portId: String, node: String, via: String, connectImmediately: Boolean) -> Unit,
) {
    val dialablePorts = ports.filter { it.config.supportsConnect() }
    var selectedPortId by remember { mutableStateOf(dialablePorts.firstOrNull()?.id) }
    var node by remember { mutableStateOf("") }
    var via by remember { mutableStateOf("") }

    val canSubmit = selectedPortId != null && node.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dial a Station") },
        text = {
            Column {
                PortDropdown(
                    ports = dialablePorts,
                    selectedId = selectedPortId,
                    onSelected = { selectedPortId = it },
                )
                NodePicker(
                    value = node,
                    onValueChange = { node = it.uppercase() },
                    suggestions = heardStations,
                    onSelect = { entry ->
                        node = entry.callsign
                        if (via.isBlank() && entry.via.isNotBlank()) via = entry.via
                    },
                )
                OutlinedTextField(
                    value = via,
                    onValueChange = { via = it.uppercase() },
                    label = { Text("Via (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(
                    enabled = canSubmit,
                    onClick = { selectedPortId?.let { onDial(it, node, via, false) } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Open Disconnected") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { selectedPortId?.let { onDial(it, node, via, true) } },
            ) { Text("Open Connected") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The "Node" field, editable, with an address-book autocomplete dropdown of [suggestions]
 * (heard stations, see [SessionViewModel.heardStations]) filtered by whatever's typed so far —
 * selecting one fills the node (and the via path too, if it's still blank).
 */
@Composable
private fun NodePicker(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<AddressBookEntry>,
    onSelect: (AddressBookEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, suggestions) {
        if (value.isBlank()) {
            emptyList()
        } else {
            suggestions.filter {
                it.callsign.contains(value, ignoreCase = true) || it.displayAlias?.contains(value, ignoreCase = true) == true
            }.take(8)
        }
    }
    val showMenu = expanded && filtered.isNotEmpty()

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text("Node") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            for (entry in filtered) {
                DropdownMenuItem(
                    text = { Text(entry.displayAlias?.let { "${entry.callsign} ($it)" } ?: entry.callsign) },
                    onClick = { onSelect(entry); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PortDropdown(ports: List<PortEntry>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ports.find { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "(no dialable ports — add an AGWPE port first)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Port") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (port in ports) {
                DropdownMenuItem(text = { Text(port.name) }, onClick = { onSelected(port.id); expanded = false })
            }
        }
    }
}
