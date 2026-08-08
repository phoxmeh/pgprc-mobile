@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HIERARCHICAL_ADDRESS_REFERENCE_URL =
    "https://ohiopacket.org/index.php/BBS_Hierarchical_Routing_Addresses"

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val uriHandler = LocalUriHandler.current
    var name by remember { mutableStateOf("") }
    var baseCallsign by remember { mutableStateOf("") }
    var callsignSsid by remember { mutableIntStateOf(0) }
    var ssidMenuExpanded by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var homeServer by remember { mutableStateOf("") }

    var netRomMinQuality by remember { mutableStateOf("1") }
    var netRomInitialObsolescence by remember { mutableStateOf("5") }
    val netRomNodes by viewModel.netRomNodes.collectAsState()
    val ports by viewModel.ports.collectAsState()
    var netRomTableExpanded by remember { mutableStateOf<String?>(null) }
    var confirmClearPortId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()
    var importExportStatus by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ioScope.launch {
            val result = viewModel.exportAddressBook(uri)
            importExportStatus = result
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ioScope.launch {
            val toml = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            val result = viewModel.importAddressBook(toml)
            importExportStatus = result
        }
    }

    LaunchedEffect(Unit) {
        val current = viewModel.loadCurrent()
        name = current.operatorName ?: ""
        val stored = current.defaultCall ?: ""
        val dashIdx = stored.lastIndexOf('-')
        if (dashIdx >= 0) {
            val ssidPart = stored.substring(dashIdx + 1).toIntOrNull()
            if (ssidPart != null && ssidPart in 0..15) {
                baseCallsign = stored.substring(0, dashIdx)
                callsignSsid = ssidPart
            } else {
                baseCallsign = stored
            }
        } else {
            baseCallsign = stored
        }
        location = current.location ?: ""
        homeServer = current.homeServer ?: ""

        val (minQ, initObs) = viewModel.loadNetRomPrefs()
        netRomMinQuality = minQ.toString()
        netRomInitialObsolescence = initObs.toString()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = baseCallsign,
                    onValueChange = { baseCallsign = it.replace(' ', '-') },
                    label = { Text("My callsign") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.weight(1f),
                )
                ExposedDropdownMenuBox(
                    expanded = ssidMenuExpanded,
                    onExpandedChange = { ssidMenuExpanded = it },
                    modifier = Modifier.padding(start = 8.dp).width(100.dp),
                ) {
                    OutlinedTextField(
                        value = "-$callsignSsid",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SSID") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ssidMenuExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(
                        expanded = ssidMenuExpanded,
                        onDismissRequest = { ssidMenuExpanded = false },
                    ) {
                        for (ssid in 0..15) {
                            DropdownMenuItem(
                                text = { Text("-$ssid") },
                                onClick = { callsignSsid = ssid; ssidMenuExpanded = false },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Location") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = homeServer,
                onValueChange = { homeServer = it.uppercase() },
                label = { Text("Home BBS address") },
                placeholder = { Text("N0CALL@WB1GOF.#EMA.MA.USA.NOAM") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Packet-BBS hierarchical routing address format: " +
                    "addressee-call@BBS-call.#local-area.state.country.continent",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Format reference (Ohio Packet wiki)",
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { uriHandler.openUri(HIERARCHICAL_ADDRESS_REFERENCE_URL) },
            )
            Button(
                onClick = {
                    val base = baseCallsign.trim().uppercase()
                    val fullCall = if (callsignSsid == 0) base else "$base-$callsignSsid"
                    viewModel.save(name, fullCall, location, homeServer)
                },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Save") }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            Text("Address Book", style = MaterialTheme.typography.titleMedium)
            Text(
                "Export/import known stations, aliases, tags, and via paths. " +
                    "Auto-populated heard statistics are not included. " +
                    "A backup is saved internally before each import.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = { exportLauncher.launch("address_book.toml") }) {
                    Text("Export")
                }
                Button(onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                    Text("Import")
                }
            }
            importExportStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("Import failed") || status.startsWith("Export failed"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            Text("NET/ROM", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = netRomMinQuality,
                    onValueChange = { netRomMinQuality = it },
                    label = { Text("Min quality (0–255)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = netRomInitialObsolescence,
                    onValueChange = { netRomInitialObsolescence = it },
                    label = { Text("Init. obsolescence") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Button(
                onClick = {
                    val minQ = netRomMinQuality.toIntOrNull()?.coerceIn(0, 255) ?: 1
                    val initObs = netRomInitialObsolescence.toIntOrNull()?.coerceIn(1, 255) ?: 5
                    netRomMinQuality = minQ.toString()
                    netRomInitialObsolescence = initObs.toString()
                    viewModel.saveNetRomPrefs(minQ, initObs)
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Save NET/ROM prefs") }

            Text(
                "Routing table (${netRomNodes.size} nodes)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            for (port in ports) {
                val portNodes = netRomNodes.filter { it.portId == port.id }
                val expanded = netRomTableExpanded == port.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        .clickable { netRomTableExpanded = if (expanded) null else port.id },
                ) {
                    Text(
                        "${port.name} (${portNodes.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
                if (expanded) {
                    if (portNodes.isEmpty()) {
                        Text(
                            "No NET/ROM nodes heard on this port.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        )
                    } else {
                        Column(Modifier.padding(start = 8.dp, top = 2.dp)) {
                            for (node in portNodes.sortedByDescending { it.quality }) {
                                Text(
                                    "${node.callsign} (${node.alias}) q=${node.quality} obs=${node.obsolescenceCounter} via ${node.bestNeighborCallsign}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { confirmClearPortId = port.id },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Clear routing table for ${port.name}") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "PGPRC Mobile 0.1.0",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "A remote packet-radio client for AGWPE, KISS-TCP, and Bluetooth KISS TNCs. " +
                    "Licensed under the MIT License.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    confirmClearPortId?.let { portId ->
        val portName = ports.find { it.id == portId }?.name ?: portId
        AlertDialog(
            onDismissRequest = { confirmClearPortId = null },
            title = { Text("Clear routing table?") },
            text = { Text("Remove all NET/ROM nodes heard on $portName?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearNetRomForPort(portId)
                    confirmClearPortId = null
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearPortId = null }) { Text("Cancel") }
            },
        )
    }
}
