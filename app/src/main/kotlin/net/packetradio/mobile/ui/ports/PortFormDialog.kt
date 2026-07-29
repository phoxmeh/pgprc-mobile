@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.AgwpeLogin
import net.packetradio.mobile.model.KissParams
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.kindLabel

private fun PortConfig?.kissParamsOrDefault(): KissParams = when (this) {
    is PortConfig.KissTcp -> kissParams
    is PortConfig.BluetoothKiss -> kissParams
    else -> KissParams()
}

private enum class FormKind { AGWPE, KISS_TCP, BLUETOOTH_KISS }

/**
 * Add/edit form for the three port kinds that already have a transport
 * ([PortConfig.Agwpe], [PortConfig.KissTcp], [PortConfig.BluetoothKiss]) —
 * USB-serial gains its own picker UI in its own transport phase (task #90),
 * and Telnet/SSH in task #91. Editing an existing port keeps its kind
 * fixed; only adding lets you pick one.
 */
@Composable
fun PortFormDialog(
    initial: PortEntry?,
    onDismiss: () -> Unit,
    onSave: (name: String, config: PortConfig, autoconnect: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PacketRadioApp

    val initialConfig = initial?.config
    var kind by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.KissTcp -> FormKind.KISS_TCP
                is PortConfig.BluetoothKiss -> FormKind.BLUETOOTH_KISS
                else -> FormKind.AGWPE
            },
        )
    }
    var name by remember { mutableStateOf(initial?.name ?: "Direwolf") }
    var host by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.Agwpe -> initialConfig.host
                is PortConfig.KissTcp -> initialConfig.host
                else -> "127.0.0.1"
            },
        )
    }
    var port by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.Agwpe -> initialConfig.port.toString()
                is PortConfig.KissTcp -> initialConfig.port.toString()
                else -> "8000"
            },
        )
    }
    var radioPort by remember {
        mutableStateOf((initialConfig as? PortConfig.Agwpe)?.radioPort?.toString() ?: "0")
    }
    var myCall by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.Agwpe -> initialConfig.myCall
                is PortConfig.KissTcp -> initialConfig.myCall
                is PortConfig.BluetoothKiss -> initialConfig.myCall
                else -> "N0CALL"
            },
        )
    }
    var autoconnect by remember { mutableStateOf(initial?.autoconnect ?: false) }

    var deviceAddress by remember { mutableStateOf((initialConfig as? PortConfig.BluetoothKiss)?.deviceAddress ?: "") }
    var deviceName by remember { mutableStateOf((initialConfig as? PortConfig.BluetoothKiss)?.deviceName ?: "") }

    val initialLogin = (initialConfig as? PortConfig.Agwpe)?.login
    var useLogin by remember { mutableStateOf(initialLogin != null) }
    var username by remember { mutableStateOf(initialLogin?.username ?: "") }
    var password by remember { mutableStateOf(initialLogin?.password ?: "") }

    val initialKissParams = initialConfig.kissParamsOrDefault()
    var useKissParams by remember {
        mutableStateOf(
            initialKissParams.txDelay != null || initialKissParams.persistence != null ||
                initialKissParams.slotTime != null || initialKissParams.fullDuplex != null,
        )
    }
    var txDelay by remember { mutableStateOf(initialKissParams.txDelay?.toString() ?: "") }
    var persistence by remember { mutableStateOf(initialKissParams.persistence?.toString() ?: "") }
    var slotTime by remember { mutableStateOf(initialKissParams.slotTime?.toString() ?: "") }
    var fullDuplex by remember { mutableStateOf(initialKissParams.fullDuplex ?: false) }

    // One-shot prefill from Settings for a brand-new port — a plain `remember` initializer would
    // race DataStore's async first emission, so fetch the current value directly instead.
    // (homeServer is a hierarchical BBS routing address, not a network host, so it has no
    // bearing on this form's Host field — see Settings for where it's actually used.)
    if (initial == null) {
        LaunchedEffect(Unit) {
            val prefs = app.preferences.uiPrefs.first()
            prefs.defaultCall?.let { if (it.isNotBlank()) myCall = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Port" else "Edit Port (${initialConfig?.kindLabel()})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initial == null) {
                    KindDropdown(
                        selected = kind,
                        onSelected = { newKind ->
                            kind = newKind
                            port = when (newKind) {
                                FormKind.AGWPE -> "8000"
                                FormKind.KISS_TCP -> "8001"
                                FormKind.BLUETOOTH_KISS -> port
                            }
                        },
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (kind == FormKind.BLUETOOTH_KISS) {
                    BluetoothDevicePicker(
                        selectedAddress = deviceAddress,
                        selectedName = deviceName,
                        onDeviceSelected = { address, devName -> deviceAddress = address; deviceName = devName },
                    )
                } else {
                    OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(port, { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (kind == FormKind.AGWPE) {
                    OutlinedTextField(radioPort, { radioPort = it }, label = { Text("Radio port") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                OutlinedTextField(myCall, { myCall = it }, label = { Text("My callsign") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoconnect, onCheckedChange = { autoconnect = it })
                    Text("Autoconnect on service start")
                }

                if (kind == FormKind.AGWPE) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useLogin, onCheckedChange = { useLogin = it })
                        Text("Requires login")
                    }
                    if (useLogin) {
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                } else {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useKissParams, onCheckedChange = { useKissParams = it })
                        Text("Custom TNC parameters")
                    }
                    if (useKissParams) {
                        OutlinedTextField(txDelay, { txDelay = it }, label = { Text("TX delay (x10ms)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        OutlinedTextField(persistence, { persistence = it }, label = { Text("Persistence (0-255)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        OutlinedTextField(slotTime, { slotTime = it }, label = { Text("Slot time (x10ms)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = fullDuplex, onCheckedChange = { fullDuplex = it })
                            Text("Full duplex")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val kissParams = if (useKissParams) {
                    KissParams(
                        txDelay = txDelay.toIntOrNull(),
                        persistence = persistence.toIntOrNull(),
                        slotTime = slotTime.toIntOrNull(),
                        fullDuplex = fullDuplex,
                    )
                } else {
                    KissParams()
                }
                val config = when (kind) {
                    FormKind.AGWPE -> {
                        val portNum = port.toIntOrNull() ?: return@TextButton
                        PortConfig.Agwpe(
                            host = host,
                            port = portNum,
                            radioPort = radioPort.toIntOrNull() ?: 0,
                            myCall = myCall,
                            login = if (useLogin) AgwpeLogin(username, password) else null,
                        )
                    }
                    FormKind.KISS_TCP -> {
                        val portNum = port.toIntOrNull() ?: return@TextButton
                        PortConfig.KissTcp(host = host, port = portNum, myCall = myCall, kissParams = kissParams)
                    }
                    FormKind.BLUETOOTH_KISS -> {
                        if (deviceAddress.isBlank()) return@TextButton
                        PortConfig.BluetoothKiss(
                            deviceAddress = deviceAddress,
                            deviceName = deviceName,
                            myCall = myCall,
                            kissParams = kissParams,
                        )
                    }
                }
                onSave(name, config, autoconnect)
                onDismiss()
            }) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun FormKind.label(): String = when (this) {
    FormKind.AGWPE -> "AGWPE"
    FormKind.KISS_TCP -> "KISS (TCP)"
    FormKind.BLUETOOTH_KISS -> "Bluetooth KISS"
}

@Composable
private fun KindDropdown(selected: FormKind, onSelected: (FormKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Kind") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in FormKind.entries) {
                DropdownMenuItem(text = { Text(kind.label()) }, onClick = { onSelected(kind); expanded = false })
            }
        }
    }
}
