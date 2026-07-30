@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private const val HIERARCHICAL_ADDRESS_REFERENCE_URL =
    "https://ohiopacket.org/index.php/BBS_Hierarchical_Routing_Addresses"

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val uriHandler = LocalUriHandler.current
    var name by remember { mutableStateOf("") }
    var callsign by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var homeServer by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val current = viewModel.loadCurrent()
        name = current.operatorName ?: ""
        callsign = current.defaultCall ?: ""
        location = current.location ?: ""
        homeServer = current.homeServer ?: ""
    }

    Scaffold(
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
        Column(Modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it.uppercase() },
                label = { Text("My callsign") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
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
                onClick = { viewModel.save(name, callsign, location, homeServer) },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Save") }

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
}
