@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package net.packetradio.mobile.ui.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.ui.common.DrawerRow

private const val CONFIRM_CLOSE_TIMEOUT_MS = 3000L

/**
 * The left-hand drawer's content: the "+"/dial action (opens [DialDialog] —
 * dialing is the only way a new tab is ever created now), the always-present
 * non-closable Monitor and Log entries, then every dialed tab (pin icon when
 * pinned, `n:node` name, tap-and-hold to pin, tap-X-twice to close), and
 * Quit/Settings entries pinned to the bottom.
 */
@Composable
fun TabsDrawerContent(
    tabs: List<SessionTabState>,
    ports: List<PortEntry>,
    frontId: String,
    monitorTabId: String,
    logTabId: String,
    onSelectTab: (String) -> Unit,
    onDial: () -> Unit,
    onCloseTab: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
) {
    val orderedTabs = remember(tabs) { tabs.sortedByDescending { it.pinned } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDial) {
                Icon(Icons.Filled.Add, contentDescription = "Dial a station")
            }
        }

        DrawerRow(
            label = "Monitor",
            selected = frontId == monitorTabId,
            onClick = { onSelectTab(monitorTabId) },
        )
        DrawerRow(
            label = "Log",
            selected = frontId == logTabId,
            onClick = { onSelectTab(logTabId) },
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(orderedTabs, key = { it.id }) { tab ->
                TabDrawerRow(
                    tab = tab,
                    label = tab.tabName(ports),
                    selected = tab.id == frontId,
                    onClick = { onSelectTab(tab.id) },
                    onLongClick = { onTogglePin(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Column(Modifier.padding(bottom = 12.dp)) {
            DrawerRow(
                label = "Quit",
                selected = false,
                leadingIcon = Icons.AutoMirrored.Filled.ExitToApp,
                onClick = onQuit,
            )
            DrawerRow(
                label = "Settings",
                selected = false,
                leadingIcon = Icons.Filled.Settings,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun TabDrawerRow(
    tab: SessionTabState,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClose: () -> Unit,
) {
    var pendingClose by remember { mutableStateOf(false) }
    LaunchedEffect(pendingClose) {
        if (pendingClose) {
            delay(CONFIRM_CLOSE_TIMEOUT_MS)
            pendingClose = false
        }
    }

    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(onClick = { pendingClose = false; onClick() }, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.pinned) {
                Icon(Icons.Filled.PushPin, contentDescription = "Pinned", modifier = Modifier.padding(end = 8.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (pendingClose) {
                IconButton(onClick = { pendingClose = false; onClose() }) {
                    Icon(Icons.Filled.Check, contentDescription = "Confirm close")
                }
            } else {
                IconButton(onClick = { pendingClose = true }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close tab")
                }
            }
        }
    }
}
