@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.needsNode

private fun PortConfig.kindIcon(): ImageVector = when (this) {
    is PortConfig.Agwpe -> Icons.Filled.Wifi
    is PortConfig.KissTcp -> Icons.Filled.Router
    is PortConfig.BluetoothKiss -> Icons.Filled.Bluetooth
    is PortConfig.UsbSerialKiss -> Icons.Filled.Usb
    is PortConfig.Telnet, is PortConfig.Ssh -> Icons.Filled.Terminal
}

@Composable
fun SessionTabContent(
    tab: SessionTabState,
    ports: List<PortEntry>,
    monitorLines: List<String>,
    portConnected: Boolean,
    onPortSelected: (String) -> Unit,
    onNodeChanged: (String) -> Unit,
    onViaChanged: (String) -> Unit,
    onToggleNodeConnection: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    val port = ports.find { it.id == tab.portId }
    val live = tab.isLive(port, portConnected)
    // Locking Node/Via/Port only while an actual node-level connection exists (never true for
    // Unproto, which has no such concept) — checking Unproto must not itself lock these fields.
    val identityEditable = tab.connectionId == null
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var monitorHeight by remember { mutableStateOf(88.dp) }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MiniMonitor(monitorLines, height = monitorHeight)
        MonitorResizeHandle(
            onDrag = { deltaPx ->
                val deltaDp = with(density) { deltaPx.toDp() }
                monitorHeight = (monitorHeight + deltaDp).coerceIn(40.dp, 400.dp)
            },
        )

        if (port?.config?.needsNode() == true && !imeVisible) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tab.node,
                    onValueChange = onNodeChanged,
                    label = { Text("Node") },
                    enabled = identityEditable,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = tab.via,
                    onValueChange = onViaChanged,
                    label = { Text("Via") },
                    enabled = identityEditable,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                CompactPortPicker(
                    ports = ports,
                    selected = port,
                    enabled = identityEditable,
                    onPortSelected = onPortSelected,
                )
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(tab.lines.size) {
            if (tab.lines.isNotEmpty()) listState.scrollToItem(tab.lines.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        ) {
            items(tab.lines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!tab.unproto && tab.inputText.isBlank()) {
                val nodeConnected = tab.connectionId != null
                IconButton(onClick = onToggleNodeConnection, enabled = portConnected) {
                    Icon(
                        if (nodeConnected) Icons.Filled.CallEnd else Icons.Filled.Call,
                        contentDescription = if (nodeConnected) "Disconnect node" else "Connect node",
                    )
                }
            }
            OutlinedTextField(
                value = tab.inputText,
                onValueChange = onInputChanged,
                label = { Text("Message") },
                enabled = live,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** An always-visible, user-resizable ([MonitorResizeHandle]) preview of the last few Monitor lines. */
@Composable
private fun MiniMonitor(monitorLines: List<String>, height: Dp) {
    val listState = rememberLazyListState()
    LaunchedEffect(monitorLines.size) {
        if (monitorLines.isNotEmpty()) listState.scrollToItem(monitorLines.size - 1)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        LazyColumn(state = listState, modifier = Modifier.padding(6.dp)) {
            items(monitorLines.takeLast(50)) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A drag handle between the mini-monitor and the rest of the tab — lets the user grow/shrink the preview. */
@Composable
private fun MonitorResizeHandle(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}

/** A single plug-icon button standing in for the port picker — tap opens the list of ports. */
@Composable
private fun CompactPortPicker(
    ports: List<PortEntry>,
    selected: PortEntry?,
    enabled: Boolean,
    onPortSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.ElectricalServices, contentDescription = selected?.name ?: "Select port")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (p in ports) {
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { Icon(p.config.kindIcon(), contentDescription = null) },
                    onClick = { onPortSelected(p.id); expanded = false },
                )
            }
        }
    }
}
