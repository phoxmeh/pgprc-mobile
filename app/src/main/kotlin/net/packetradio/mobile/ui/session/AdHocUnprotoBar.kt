@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.supportsUnproto

/**
 * The Monitor screen's always-available freeform unproto compose bar — the
 * only way to use a KISS-only port (which never appears in [DialDialog]'s
 * connect-capable port list), and the general-purpose "send a one-shot UI
 * frame" surface now that session tabs are always dialed two-way sessions.
 */
@Composable
fun AdHocUnprotoBar(
    ports: List<PortEntry>,
    state: AdHocUnprotoState,
    onPortSelected: (String) -> Unit,
    onNodeChanged: (String) -> Unit,
    onViaChanged: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    val unprotoPorts = ports.filter { it.config.supportsUnproto() }
    val selectedPort = unprotoPorts.find { it.id == state.portId }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AdHocPortDropdown(ports = unprotoPorts, selected = selectedPort, onPortSelected = onPortSelected)
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = state.node,
            onValueChange = { onNodeChanged(it.replace(' ', '-')) },
            label = { Text("Node") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = state.via,
            onValueChange = { onViaChanged(it.replace(' ', '-')) },
            label = { Text("Via") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.inputText,
            onValueChange = onInputChanged,
            label = { Text("Unproto message") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = state.portId != null && state.node.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun AdHocPortDropdown(ports: List<PortEntry>, selected: PortEntry?, onPortSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "(no unproto-capable ports)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Port") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.width(140.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (port in ports) {
                DropdownMenuItem(text = { Text(port.name) }, onClick = { onPortSelected(port.id); expanded = false })
            }
        }
    }
}
