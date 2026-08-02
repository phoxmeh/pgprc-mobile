@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import net.packetradio.mobile.model.AddressBookEntry
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.supportsConnect

/**
 * Dials a new session tab — the only way one is ever created (mirrors the
 * desktop's Ctrl+N dial dialog). Only ports whose kind actually supports a
 * two-way AX.25 connection are offered here — AGWPE, KISS-TCP, and Bluetooth
 * KISS; USB-serial KISS isn't wired up yet and stays unproto-only on the
 * Monitor screen's ad-hoc bar for now (see [net.packetradio.mobile.model.supportsConnect]).
 *
 * Node/via are stored as typed (no live uppercase) — uppercase is applied on
 * submit so the IME doesn't trigger a re-layout on every keystroke. Space is
 * intercepted and replaced with hyphen since neither callsigns nor digipeater
 * paths ever contain spaces.
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
    var showAddressBook by remember { mutableStateOf(false) }

    val selectedPort = dialablePorts.find { it.id == selectedPortId }
    val isTelnet = selectedPort?.config is PortConfig.Telnet
    val canSubmit = selectedPortId != null && (isTelnet || node.isNotBlank())

    fun submit(connectImmediately: Boolean) {
        selectedPortId?.let { onDial(it, node.trim().uppercase(), via.trim().uppercase(), connectImmediately) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text("Dial a Station", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                PortDropdown(
                    ports = dialablePorts,
                    selectedId = selectedPortId,
                    onSelected = { selectedPortId = it },
                )
                if (!isTelnet) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        NodePicker(
                            value = node,
                            onValueChange = { node = it.replace(' ', '-') },
                            suggestions = heardStations,
                            onSelect = { entry ->
                                node = entry.callsign
                                if (via.isBlank() && entry.via.isNotBlank()) via = entry.via
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { showAddressBook = true },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Filled.Contacts, contentDescription = "Address book")
                        }
                    }
                    OutlinedTextField(
                        value = via,
                        onValueChange = { via = it.replace(' ', '-') },
                        label = { Text("Via (optional)") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(enabled = canSubmit, onClick = { submit(false) }) { Text("Open Disconnected") }
                    TextButton(enabled = canSubmit, onClick = { submit(true) }) { Text("Dial") }
                }
            }
        }
    }

    if (showAddressBook) {
        AddressBookPickerDialog(
            stations = heardStations,
            onSelect = { entry ->
                node = entry.callsign
                if (via.isBlank() && entry.via.isNotBlank()) via = entry.via
                showAddressBook = false
            },
            onDismiss = { showAddressBook = false },
        )
    }
}

/**
 * Full browseable address book picker — shows all [stations] in a scrollable,
 * filterable list. Selecting one fills the dial form; no editing here.
 */
@Composable
private fun AddressBookPickerDialog(
    stations: List<AddressBookEntry>,
    onSelect: (AddressBookEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sorted = remember(stations) { stations.sortedBy { it.callsign } }
    val filtered = remember(query, sorted) {
        if (query.isBlank()) sorted
        else sorted.filter {
            it.callsign.contains(query, ignoreCase = true) ||
                it.displayAlias?.contains(query, ignoreCase = true) == true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Address Book") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 4.dp)) {
                    items(filtered, key = { it.callsign }) { entry ->
                        TextButton(
                            onClick = { onSelect(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                entry.displayAlias?.let { "${entry.callsign} ($it)" } ?: entry.callsign,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    modifier: Modifier = Modifier,
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

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text("Node") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        // focusable = false keeps the keyboard visible when the suggestions dropdown appears
        DropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = false)) {
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
