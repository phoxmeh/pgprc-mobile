@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.heard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.packetradio.mobile.model.CallsignEntry

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
        val byFilter = if (filter.isBlank()) entries else entries.filter { entry ->
            entry.baseCallsign.contains(filter, ignoreCase = true) ||
                entry.ssids.any { it.displayAlias?.contains(filter, ignoreCase = true) == true }
        }
        val sorted = when (sortMode) {
            SortMode.CALL -> byFilter.sortedBy { it.baseCallsign }
            SortMode.LAST_HEARD -> byFilter.sortedByDescending { entry ->
                entry.ssids.mapNotNull { it.lastHeard }.maxOrNull().orEmpty()
            }
        }
        if (!sortAscending) sorted else sorted.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Known Nodes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { sortAscending = !sortAscending }) {
                        Icon(
                            if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = if (sortAscending) "Ascending" else "Descending",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
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
                items(filtered, key = { it.baseCallsign }) { entry ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                            pendingDelete = entry.baseCallsign
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
                        CallsignRow(entry, onClick = { onOpenStation(entry.baseCallsign) })
                    }
                }
            }
        }
    }

    pendingDelete?.let { base ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove $base?") },
            text = { Text("This will remove $base and all its known SSIDs. They will re-appear the next time a frame from this station is heard.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCallsign(base); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CallsignRow(entry: CallsignEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.baseCallsign, style = MaterialTheme.typography.bodyLarge)
                entry.operatorName?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.ssids.isNotEmpty()) {
                val ssidText = entry.ssids.joinToString(", ") { ssid ->
                    val suffix = if (ssid.ssidNumber == 0) "-0" else "-${ssid.ssidNumber}"
                    ssid.displayAlias?.let { "$suffix ($it)" } ?: suffix
                }
                Text(
                    ssidText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
