@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.heard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.packetradio.mobile.model.AddressBookEntry

private val IndirectDotColor = Color(0xFFFFCC80)

private enum class SortMode(val label: String) { CALL("Call"), LAST_HEARD("Last Heard") }

@Composable
fun HeardStationsScreen(
    onBack: () -> Unit,
    onOpenStation: (String) -> Unit,
    viewModel: HeardStationsViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    var filter by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.LAST_HEARD) }
    var sortAscending by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val filtered = remember(entries, filter, sortMode, sortAscending) {
        val byFilter = if (filter.isBlank()) {
            entries
        } else {
            entries.filter {
                it.callsign.contains(filter, ignoreCase = true) || it.displayAlias?.contains(filter, ignoreCase = true) == true
            }
        }
        val sorted = when (sortMode) {
            SortMode.CALL -> byFilter.sortedBy { it.callsign }
            SortMode.LAST_HEARD -> byFilter.sortedByDescending { it.lastHeard.orEmpty() }
        }
        if (sortAscending) sorted else sorted.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heard Stations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Direction toggle — always visible so the current direction is always clear
                    IconButton(onClick = { sortAscending = !sortAscending }) {
                        Icon(
                            if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = if (sortAscending) "Ascending" else "Descending",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Sort-field picker — checkmark on active item
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort by")
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            for (mode in SortMode.entries) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        if (sortMode == mode) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    text = { Text(mode.label) },
                                    onClick = { sortMode = mode; sortMenuExpanded = false },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.callsign }) { entry ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                            pendingDelete = entry.callsign
                            dismissState.reset()
                        }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            val bg by animateColorAsState(
                                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else Color.Transparent,
                                label = "delete-bg",
                            )
                            Box(
                                Modifier.fillMaxSize().padding(vertical = 3.dp).background(bg, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(end = 16.dp),
                                )
                            }
                        },
                    ) {
                        HeardStationRow(entry, onClick = { onOpenStation(entry.callsign) })
                    }
                }
            }
        }
    }

    pendingDelete?.let { callsign ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove $callsign?") },
            text = { Text("This will remove $callsign from the heard stations list. It will re-appear the next time a frame from this station is heard.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteEntry(callsign); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HeardStationRow(entry: AddressBookEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!entry.heardDirectly) {
                Box(Modifier.padding(end = 8.dp).size(8.dp).background(IndirectDotColor, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.callsign, style = MaterialTheme.typography.bodyLarge)
                    entry.displayAlias?.let {
                        Text(
                            " ($it)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Heard ${entry.heardCount}x" + (entry.lastHeard?.let { " · last $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
