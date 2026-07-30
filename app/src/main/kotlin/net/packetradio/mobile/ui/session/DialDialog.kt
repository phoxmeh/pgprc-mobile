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
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.supportsConnect

/**
 * Dials a new session tab — the only way one is ever created (mirrors the
 * desktop's Ctrl+N dial dialog). Only ports whose kind actually supports a
 * two-way AX.25 connection are offered here; KISS-only ports never appear
 * (they're unproto-only and live on the Monitor screen's ad-hoc bar instead).
 */
@Composable
fun DialDialog(
    ports: List<PortEntry>,
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
                OutlinedTextField(
                    value = node,
                    onValueChange = { node = it.uppercase() },
                    label = { Text("Node") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
