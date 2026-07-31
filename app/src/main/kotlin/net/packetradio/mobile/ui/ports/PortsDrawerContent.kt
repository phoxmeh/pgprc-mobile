@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.kindLabel
import net.packetradio.mobile.ui.common.DrawerRow
import net.packetradio.mobile.ui.session.PortStatus

private val ConnectedColor = Color(0xFF2E7D32)
private val ErrorColor = Color(0xFFF9A825)

/**
 * The right-hand drawer's content: every configured port as a full-width
 * toggle button (tap = connect/disconnect that transport, no AX.25 involved
 * — see `SessionViewModel.togglePort`), colored by [PortStatus]. Edit/
 * delete/reorder live behind each row's overflow icon instead of their own
 * dedicated buttons, keeping the row itself a clean single toggle.
 */
@Composable
fun PortsDrawerContent(
    ports: List<PortEntry>,
    portStatuses: Map<String, PortStatus>,
    onTogglePort: (String) -> Unit,
    onAddPort: (name: String, config: PortConfig, autoconnect: Boolean) -> Unit,
    onUpdatePort: (PortEntry) -> Unit,
    onDeletePort: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenHeardStations: () -> Unit = {},
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PortEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<PortEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ports", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add Port")
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(ports, key = { it.id }) { entry ->
                val index = ports.indexOf(entry)
                PortToggleRow(
                    index = index,
                    entry = entry,
                    status = portStatuses[entry.id] ?: PortStatus.OFF,
                    isFirst = index == 0,
                    isLast = index == ports.lastIndex,
                    onToggle = { onTogglePort(entry.id) },
                    onEdit = { editing = entry },
                    onDelete = { pendingDelete = entry },
                    onMoveUp = { onMoveUp(entry.id) },
                    onMoveDown = { onMoveDown(entry.id) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        // Unlike every other drawer surface (which only needs statusBarsPadding — see
        // SessionScreen), this bottom block sits flush against the drawer's own bottom edge, so
        // it's the one place that needs to additionally clear the gesture/nav-bar inset itself.
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            DrawerRow(
                label = "Notifications",
                selected = false,
                leadingIcon = Icons.Filled.Notifications,
                onClick = onOpenNotifications,
            )
            DrawerRow(
                label = "Heard Stations",
                selected = false,
                leadingIcon = Icons.Filled.Radar,
                onClick = onOpenHeardStations,
            )
        }
    }

    if (showAdd) {
        PortFormDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { name, config, autoconnect -> onAddPort(name, config, autoconnect) },
        )
    }

    editing?.let { entry ->
        PortFormDialog(
            initial = entry,
            onDismiss = { editing = null },
            onSave = { name, config, autoconnect -> onUpdatePort(entry.copy(name = name, config = config, autoconnect = autoconnect)) },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete port?") },
            text = { Text("\"${entry.name}\" will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onDeletePort(entry.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PortToggleRow(
    index: Int,
    entry: PortEntry,
    status: PortStatus,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val color = when (status) {
        PortStatus.OFF -> MaterialTheme.colorScheme.surfaceVariant
        PortStatus.CONNECTED -> ConnectedColor
        PortStatus.ERROR -> ErrorColor
    }
    val onColor = when (status) {
        PortStatus.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.White
    }

    Surface(
        onClick = onToggle,
        color = color,
        contentColor = onColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$index", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(entry.config.kindLabel(), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Port options", tint = onColor)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = !isFirst, onClick = { onMoveUp(); menuExpanded = false })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = !isLast, onClick = { onMoveDown(); menuExpanded = false })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); menuExpanded = false })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); menuExpanded = false })
                }
            }
        }
    }
}
