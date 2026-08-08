@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.packetradio.mobile.model.NetRomNodeEntry
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.SsidEntry
import net.packetradio.mobile.model.supportsConnect

/**
 * Dials a new session tab — the only way one is ever created (mirrors the
 * desktop's Ctrl+N dial dialog). Only ports whose kind actually supports a
 * two-way AX.25 connection are offered here — AGWPE, KISS-TCP, and Bluetooth
 * KISS; USB-serial KISS isn't wired up yet and stays unproto-only on the
 * Monitor screen's ad-hoc bar for now (see [net.packetradio.mobile.model.supportsConnect]).
 * Telnet ports are also excluded here — they auto-open a terminal tab on connect.
 *
 * Node/via are stored as typed (no live uppercase) — uppercase is applied on
 * submit so the IME doesn't trigger a re-layout on every keystroke. Space is
 * intercepted and replaced with hyphen since neither callsigns nor digipeater
 * paths ever contain spaces.
 *
 * The node autocomplete only shows directly-heard stations or those reachable
 * via a single NET/ROM hop (i.e. with a non-blank `via` field) — multi-hop
 * NODES entries are excluded to keep the list practical.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialDialog(
    ports: List<PortEntry>,
    heardStations: List<SsidEntry> = emptyList(),
    netRomNodes: List<NetRomNodeEntry> = emptyList(),
    onDismiss: () -> Unit,
    onDial: (portId: String, node: String, via: String, tag: String?, netRomGateway: String?, connectImmediately: Boolean) -> Unit,
) {
    val dialablePorts = ports.filter { it.config.supportsConnect() }
    var selectedPortId by remember { mutableStateOf(dialablePorts.firstOrNull()?.id) }
    var node by remember { mutableStateOf("") }
    var via by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<SsidEntry?>(null) }
    var selectedNetRomNode by remember { mutableStateOf<NetRomNodeEntry?>(null) }
    var showAddressBook by remember { mutableStateOf(false) }

    // Only offer directly-heard or single-hop-reachable nodes in the inline autocomplete
    val dialableStations = remember(heardStations) {
        heardStations.filter { it.heardDirectly || it.viaPaths.isNotEmpty() }
    }

    val canSubmit = selectedPortId != null && node.isNotBlank()

    fun resolvedNode(): String {
        val trimmed = node.trim().uppercase()
        val exactAlias = heardStations.firstOrNull { it.displayAlias?.equals(trimmed, ignoreCase = true) == true }
        return exactAlias?.fullCallsign ?: trimmed
    }

    fun submit(connectImmediately: Boolean) {
        selectedPortId?.let {
            val resolvedCallsign = resolvedNode()
            val tag = selectedEntry?.tag ?: heardStations
                .firstOrNull { it.fullCallsign.equals(resolvedCallsign, ignoreCase = true) }?.tag
            val netRomGateway = selectedNetRomNode?.bestNeighborCallsign
            onDial(it, resolvedCallsign, via.trim().uppercase(), tag, netRomGateway, connectImmediately)
        }
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
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NodePicker(
                        value = node,
                        onValueChange = { new ->
                            node = new.replace(' ', '-')
                            selectedEntry = null
                            selectedNetRomNode = null
                        },
                        suggestions = dialableStations,
                        netRomNodes = netRomNodes,
                        onSelect = { entry ->
                            node = entry.fullCallsign
                            selectedEntry = entry
                            selectedNetRomNode = null
                            if (via.isBlank()) via = entry.viaPaths.firstOrNull().orEmpty()
                        },
                        onSelectNetRom = { netRom ->
                            node = netRom.callsign
                            selectedNetRomNode = netRom
                            selectedEntry = null
                            via = ""
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
                if (selectedNetRomNode != null) {
                    Text(
                        "Via NET/ROM gateway: ${selectedNetRomNode!!.bestNeighborCallsign}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = via,
                        onValueChange = { via = it.replace(' ', '-') },
                        label = { Text("Via (optional)") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                // Saved via path suggestions for the selected SSID
                val viaSuggestions = if (selectedNetRomNode == null) selectedEntry?.viaPaths ?: emptyList() else emptyList()
                if (viaSuggestions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        for (path in viaSuggestions) {
                            FilterChip(
                                selected = via.trim().uppercase() == path.uppercase(),
                                onClick = { via = path },
                                label = { Text(path, style = MaterialTheme.typography.bodySmall) },
                            )
                        }
                    }
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
                node = entry.fullCallsign
                selectedEntry = entry
                if (via.isBlank()) via = entry.viaPaths.firstOrNull().orEmpty()
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
    stations: List<SsidEntry>,
    onSelect: (SsidEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sorted = remember(stations) { stations.sortedBy { it.fullCallsign } }
    val filtered = remember(query, sorted) {
        if (query.isBlank()) sorted
        else sorted.filter {
            it.fullCallsign.contains(query, ignoreCase = true) ||
                it.displayAlias?.contains(query, ignoreCase = true) == true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Known Nodes") },
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
                Column(Modifier.padding(top = 4.dp)) {
                    for (entry in filtered.take(20)) {
                        TextButton(
                            onClick = { onSelect(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                entry.displayAlias?.let { "${entry.fullCallsign} ($it)" } ?: entry.fullCallsign,
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
 * Node field with an inline (non-popup) suggestion list below it showing [suggestions] (address
 * book) and [netRomNodes] (routing table) filtered to what's been typed. Address-book entries
 * are shown first; NET/ROM routing-table nodes are shown with a "[N*]" marker.
 */
@Composable
private fun NodePicker(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<SsidEntry>,
    netRomNodes: List<NetRomNodeEntry> = emptyList(),
    onSelect: (SsidEntry) -> Unit,
    onSelectNetRom: (NetRomNodeEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val filteredAddressBook = remember(value, suggestions) {
        if (value.isBlank()) emptyList()
        else suggestions.filter {
            it.fullCallsign.contains(value, ignoreCase = true) ||
                it.displayAlias?.contains(value, ignoreCase = true) == true
        }.take(5)
    }
    val filteredNetRom = remember(value, netRomNodes) {
        if (value.isBlank()) emptyList()
        else netRomNodes.filter {
            it.callsign.contains(value, ignoreCase = true) ||
                it.alias.contains(value, ignoreCase = true)
        }.take(3)
    }
    val hasResults = filteredAddressBook.isNotEmpty() || filteredNetRom.isNotEmpty()

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Node") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (hasResults) {
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                Column {
                    for (entry in filteredAddressBook) {
                        TextButton(
                            onClick = { onSelect(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Text(
                                buildAnnotatedString {
                                    append(entry.fullCallsign)
                                    entry.displayAlias?.let { append(" ($it)") }
                                    entry.tag?.let {
                                        append(" ")
                                        withStyle(SpanStyle(color = mutedColor)) { append("#$it") }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    for (netRom in filteredNetRom) {
                        TextButton(
                            onClick = { onSelectNetRom(netRom) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Text(
                                buildAnnotatedString {
                                    append(netRom.callsign)
                                    if (netRom.alias.isNotBlank()) append(" (${netRom.alias})")
                                    append(" ")
                                    withStyle(SpanStyle(color = mutedColor)) { append("[N*]") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
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
