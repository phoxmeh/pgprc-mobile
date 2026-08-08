@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.heard

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.packetradio.mobile.model.CallsignEntry
import net.packetradio.mobile.model.HeardBeaconPacket
import net.packetradio.mobile.model.SsidEntry

@Composable
fun AddressBookEntryScreen(
    baseCallsign: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: HeardStationsViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val entry = entries.find { it.baseCallsign == baseCallsign }
    val beacons by remember(baseCallsign) {
        viewModel.observeBeaconsForBase(baseCallsign)
    }.collectAsState(initial = emptyList())

    var showCallsignEditDialog by remember { mutableStateOf(false) }
    var showDeleteCallsignConfirm by remember { mutableStateOf(false) }
    var ssidToEdit by remember { mutableStateOf<SsidEntry?>(null) }
    var ssidToDelete by remember { mutableStateOf<SsidEntry?>(null) }
    var showAddSsidDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(baseCallsign) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCallsignEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit callsign info")
                    }
                    IconButton(onClick = { showDeleteCallsignConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove callsign")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (entry == null) {
                Text("Not found.", style = MaterialTheme.typography.bodyMedium)
                return@Scaffold
            }

            // --- Callsign-level info ---
            ReadOnlyField("Name", entry.name)
            ReadOnlyField("Location", entry.location)

            // --- QRZ operator data ---
            val hasQrzInfo = listOf(entry.firstName, entry.lastName, entry.address, entry.email).any { !it.isNullOrBlank() }
            if (hasQrzInfo) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "From QRZ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ReadOnlyField("First name", entry.firstName)
                ReadOnlyField("Last name", entry.lastName)
                ReadOnlyField("Address", entry.address)
                ReadOnlyField("Email", entry.email)
            }

            // --- SSID list ---
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SSIDs", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showAddSsidDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add SSID")
                }
            }

            if (entry.ssids.isEmpty()) {
                Text(
                    "No SSIDs recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                for (ssid in entry.ssids) {
                    SsidRow(
                        ssid = ssid,
                        onEdit = { ssidToEdit = ssid },
                        onDelete = { ssidToDelete = ssid },
                    )
                }
            }

            // --- Heard beacons / ID packets ---
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Heard Beacons / ID Packets", style = MaterialTheme.typography.titleMedium)
            if (beacons.isEmpty()) {
                Text(
                    "No beacons heard yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                for (beacon in beacons) {
                    BeaconRow(beacon)
                }
            }

            // --- Notes ---
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            NotesField(
                notes = entry.notes ?: "",
                onSave = { viewModel.updateCallsign(baseCallsign, entry.name, entry.location, it) },
            )

            // --- Map preview ---
            val mapLat = entry.lat
            val mapLon = entry.lon
            if (mapLat != null && mapLon != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Location", style = MaterialTheme.typography.titleMedium)
                val html = remember(mapLat, mapLon) { mapHtml(mapLat, mapLon) }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            isNestedScrollingEnabled = false
                            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(top = 8.dp),
                )
            }
        }
    }

    // Dialogs
    if (showCallsignEditDialog && entry != null) {
        CallsignEditDialog(
            entry = entry,
            onDismiss = { showCallsignEditDialog = false },
            onSave = { name, location ->
                viewModel.updateCallsign(baseCallsign, name, location, entry.notes)
                showCallsignEditDialog = false
            },
        )
    }

    if (showDeleteCallsignConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteCallsignConfirm = false },
            title = { Text("Remove $baseCallsign?") },
            text = { Text("This removes $baseCallsign and all its SSIDs from Known Nodes.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCallsign(baseCallsign)
                    showDeleteCallsignConfirm = false
                    onDeleted()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDeleteCallsignConfirm = false }) { Text("Cancel") } },
        )
    }

    ssidToEdit?.let { ssid ->
        SsidEditDialog(
            ssid = ssid,
            existingSsidNumbers = entry?.ssids?.map { it.ssidNumber } ?: emptyList(),
            onDismiss = { ssidToEdit = null },
            onSave = { ssidNum, alias, tag, vias ->
                viewModel.updateSsid(baseCallsign, ssidNum, alias, tag, vias)
                ssidToEdit = null
            },
        )
    }

    ssidToDelete?.let { ssid ->
        AlertDialog(
            onDismissRequest = { ssidToDelete = null },
            title = { Text("Remove ${ssid.fullCallsign}?") },
            text = { Text("This removes this SSID entry from Known Nodes.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSsid(baseCallsign, ssid.ssidNumber)
                    ssidToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { ssidToDelete = null }) { Text("Cancel") } },
        )
    }

    if (showAddSsidDialog) {
        SsidEditDialog(
            ssid = null,
            existingSsidNumbers = entry?.ssids?.map { it.ssidNumber } ?: emptyList(),
            onDismiss = { showAddSsidDialog = false },
            onSave = { ssidNum, alias, tag, vias ->
                viewModel.addSsid(baseCallsign, ssidNum, alias, tag, vias)
                showAddSsidDialog = false
            },
        )
    }
}

@Composable
private fun SsidRow(ssid: SsidEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val suffix = if (ssid.ssidNumber == 0) "-0" else "-${ssid.ssidNumber}"
                    Text(ssid.fullCallsign, style = MaterialTheme.typography.bodyLarge)
                    ssid.displayAlias?.let {
                        Text(
                            " : $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ssid.tag?.let {
                        Text(
                            "  #$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val stats = listOfNotNull(
                    ssid.lastHeard?.let { "Last heard $it" },
                    "Heard ${ssid.heardCount}x",
                    if (!ssid.heardDirectly) "indirect" else null,
                ).joinToString(" · ")
                if (stats.isNotEmpty()) {
                    Text(stats, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ssid.viaPaths.isNotEmpty()) {
                    Text(
                        "Via: " + ssid.viaPaths.joinToString(" | "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit SSID")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete SSID")
            }
        }
    }
}

@Composable
private fun BeaconRow(beacon: HeardBeaconPacket) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(beacon.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                beacon.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotesField(notes: String, onSave: (String) -> Unit) {
    var text by remember(notes) { mutableStateOf(notes) }
    Text("Notes", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        minLines = 3,
    )
    TextButton(
        onClick = { onSave(text) },
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("Save Notes") }
}

@Composable
private fun CallsignEditDialog(
    entry: CallsignEntry,
    onDismiss: () -> Unit,
    onSave: (name: String?, location: String?) -> Unit,
) {
    var name by remember { mutableStateOf(entry.name ?: "") }
    var location by remember { mutableStateOf(entry.location ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${entry.baseCallsign}") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.ifBlank { null }, location.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Edit dialog for one SSID entry. Pass [ssid] = null to create a new one. */
@Composable
private fun SsidEditDialog(
    ssid: SsidEntry?,
    existingSsidNumbers: List<Int>,
    onDismiss: () -> Unit,
    onSave: (ssidNumber: Int, alias: String?, tag: String?, viaPaths: List<String>) -> Unit,
) {
    var ssidNumber by remember { mutableIntStateOf(ssid?.ssidNumber ?: (0..15).firstOrNull { it !in existingSsidNumbers } ?: 0) }
    var ssidMenuExpanded by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(ssid?.userAlias ?: "") }
    var tag by remember { mutableStateOf(ssid?.tag ?: "") }
    var vias by remember { mutableStateOf(if (ssid != null) ssid.viaPaths.toMutableList() else mutableListOf("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ssid == null) "Add SSID" else "Edit ${ssid.fullCallsign}") },
        text = {
            Column {
                // SSID number picker
                ExposedDropdownMenuBox(
                    expanded = ssidMenuExpanded,
                    onExpandedChange = { ssidMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    OutlinedTextField(
                        value = "-$ssidNumber",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SSID") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ssidMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(
                        expanded = ssidMenuExpanded,
                        onDismissRequest = { ssidMenuExpanded = false },
                    ) {
                        for (n in 0..15) {
                            DropdownMenuItem(
                                text = { Text("-$n") },
                                onClick = { ssidNumber = n; ssidMenuExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Tag (e.g. BBS, chat, digi)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                // Via paths list
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Via paths", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { vias = (vias + "").toMutableList() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add via path")
                    }
                }
                for (i in vias.indices) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = vias[i],
                            onValueChange = { new -> vias = vias.toMutableList().also { it[i] = new.replace(' ', ',') } },
                            label = { Text("Path ${i + 1}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.weight(1f),
                        )
                        if (vias.size > 1) {
                            IconButton(onClick = { vias = vias.toMutableList().also { it.removeAt(i) } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove path")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ssidNumber, alias.ifBlank { null }, tag.ifBlank { null }, vias.filter { it.isNotBlank() })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun mapHtml(lat: Double, lon: Double) = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{width:100%;height:100%;margin:0;padding:0;overflow:hidden}</style>
</head><body>
<div id="map"></div>
<script>
  var map = L.map('map', {zoomControl:true, attributionControl:false, scrollWheelZoom:false})
    .setView([$lat, $lon], 12);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19}).addTo(map);
  L.marker([$lat, $lon]).addTo(map);
</script>
</body></html>
""".trimIndent()
