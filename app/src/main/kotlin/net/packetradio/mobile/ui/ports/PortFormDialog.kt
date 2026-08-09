@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.first
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.AfskSettings
import net.packetradio.mobile.model.AgwpeLogin
import net.packetradio.mobile.model.KissParams
import net.packetradio.mobile.model.ModemMode
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.kindLabel

private fun PortConfig?.kissParamsOrDefault(): KissParams = when (this) {
    is PortConfig.KissTcp -> kissParams
    is PortConfig.BluetoothKiss -> kissParams
    else -> KissParams()
}

private enum class FormKind { AGWPE, KISS_TCP, BLUETOOTH_KISS, TELNET, USB_AUDIO }

/**
 * Add/edit form for all supported port kinds. Editing an existing port keeps
 * its kind fixed; only adding lets you pick one.
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
                is PortConfig.Telnet -> FormKind.TELNET
                is PortConfig.UsbAudio -> FormKind.USB_AUDIO
                else -> FormKind.AGWPE
            },
        )
    }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.Agwpe -> initialConfig.host
                is PortConfig.KissTcp -> initialConfig.host
                is PortConfig.Telnet -> initialConfig.host
                else -> "127.0.0.1"
            },
        )
    }
    var port by remember {
        mutableStateOf(
            when (initialConfig) {
                is PortConfig.Agwpe -> initialConfig.port.toString()
                is PortConfig.KissTcp -> initialConfig.port.toString()
                is PortConfig.Telnet -> initialConfig.port.toString()
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
                is PortConfig.UsbAudio -> initialConfig.myCall
                else -> "N0CALL"
            },
        )
    }
    var autoconnect by remember { mutableStateOf(initial?.autoconnect ?: false) }

    var deviceAddress by remember { mutableStateOf((initialConfig as? PortConfig.BluetoothKiss)?.deviceAddress ?: "") }
    var deviceName by remember { mutableStateOf((initialConfig as? PortConfig.BluetoothKiss)?.deviceName ?: "") }

    val digirigInitial = initialConfig as? PortConfig.UsbAudio
    var digirigSerialVendorId by remember { mutableStateOf(digirigInitial?.serialVendorId) }
    var digirigSerialProductId by remember { mutableStateOf(digirigInitial?.serialProductId) }
    var digirigSerialName by remember { mutableStateOf(digirigInitial?.serialDeviceName ?: "") }
    var digirigSerialPortIndex by remember { mutableStateOf(digirigInitial?.serialPortIndex ?: 0) }
    var digirigAudioProductName by remember { mutableStateOf(digirigInitial?.audioProductName ?: "") }
    var modemMode by remember { mutableStateOf(digirigInitial?.modemMode ?: ModemMode.BELL_202_1200) }
    var afskSettings by remember { mutableStateOf(digirigInitial?.afskSettings ?: AfskSettings()) }
    var showAfskSettings by remember { mutableStateOf(false) }
    var showLevelMeter by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }

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

    // Request RECORD_AUDIO when USB Audio/PTT kind is active (needed for AudioRecord)
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* permission result handled by the OS; AudioRecord checks it at use time */ }
    LaunchedEffect(kind) {
        if (kind == FormKind.USB_AUDIO &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                                FormKind.BLUETOOTH_KISS, FormKind.USB_AUDIO -> port
                                FormKind.TELNET -> "23"
                            }
                        },
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                when (kind) {
                    FormKind.BLUETOOTH_KISS -> BluetoothDevicePicker(
                        selectedAddress = deviceAddress,
                        selectedName = deviceName,
                        onDeviceSelected = { address, devName -> deviceAddress = address; deviceName = devName },
                    )
                    FormKind.USB_AUDIO -> {
                        UsbSerialDevicePicker(
                            selectedVendorId = digirigSerialVendorId,
                            selectedProductId = digirigSerialProductId,
                            selectedName = digirigSerialName,
                            selectedPortIndex = digirigSerialPortIndex,
                            onDeviceSelected = { vid, pid, name, portIdx ->
                                digirigSerialVendorId = vid
                                digirigSerialProductId = pid
                                digirigSerialName = name
                                digirigSerialPortIndex = portIdx
                            },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            UsbAudioDevicePicker(
                                selectedProductName = digirigAudioProductName,
                                onDeviceSelected = { digirigAudioProductName = it },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showLevelMeter = true }) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Test audio input level",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (showLevelMeter) {
                            AudioLevelMeterDialog(
                                audioProductName = digirigAudioProductName,
                                onDismiss = { showLevelMeter = false },
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ModemModePicker(
                                selected = modemMode,
                                onSelected = { modemMode = it },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showAfskSettings = true }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Modem settings",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (showAfskSettings) {
                            AfskSettingsDialog(
                                settings = afskSettings,
                                onSave = { afskSettings = it; showAfskSettings = false },
                                onDismiss = { showAfskSettings = false },
                            )
                        }
                    }
                    else -> {
                        OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        OutlinedTextField(port, { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                if (kind == FormKind.AGWPE) {
                    OutlinedTextField(radioPort, { radioPort = it }, label = { Text("Radio port") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (kind != FormKind.TELNET) {
                    OutlinedTextField(myCall, { myCall = it.uppercase() }, label = { Text("My callsign") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoconnect, onCheckedChange = { autoconnect = it })
                    Text("Autoconnect on service start")
                }

                when (kind) {
                    FormKind.AGWPE -> {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useLogin, onCheckedChange = { useLogin = it })
                            Text("Requires login")
                        }
                        if (useLogin) {
                            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    FormKind.KISS_TCP, FormKind.BLUETOOTH_KISS -> {
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
                    FormKind.TELNET, FormKind.USB_AUDIO -> {} // no TNC parameters for these kinds
                }
                if (saveError.isNotBlank()) {
                    Text(
                        text = saveError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
                    FormKind.TELNET -> {
                        val portNum = port.toIntOrNull() ?: return@TextButton
                        PortConfig.Telnet(host = host, port = portNum)
                    }
                    FormKind.USB_AUDIO -> {
                        val vid = digirigSerialVendorId
                        val pid = digirigSerialProductId
                        if (vid == null || pid == null) {
                            saveError = "Select a connected USB serial device first."
                            return@TextButton
                        }
                        saveError = ""
                        PortConfig.UsbAudio(
                            serialVendorId = vid,
                            serialProductId = pid,
                            serialDeviceName = digirigSerialName,
                            audioProductName = digirigAudioProductName,
                            serialPortIndex = digirigSerialPortIndex,
                            myCall = myCall,
                            modemMode = modemMode,
                            afskSettings = afskSettings,
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
    FormKind.TELNET -> "Telnet"
    FormKind.USB_AUDIO -> "USB Audio/PTT"
}

@Composable
fun AfskSettingsDialog(
    settings: AfskSettings,
    onSave: (AfskSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var txDelay    by remember { mutableStateOf(if (settings.txDelayMs < 0) "" else settings.txDelayMs.toString()) }
    var preamble   by remember { mutableStateOf(settings.preambleFlags.toString()) }
    var tail       by remember { mutableStateOf(settings.tailMs.toString()) }
    var persist    by remember { mutableStateOf(settings.persist.toString()) }
    var slotTime   by remember { mutableStateOf(settings.slotTimeMs.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modem Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "TX Delay — time (ms) PTT is held before audio starts. Leave blank to use the mode default (300 ms VHF / 500 ms HF).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = txDelay,
                    onValueChange = { txDelay = it.filter { c -> c.isDigit() } },
                    label = { Text("TX Delay (ms, blank = auto)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = preamble,
                    onValueChange = { preamble = it.filter { c -> c.isDigit() } },
                    label = { Text("Preamble flags (default 20)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = tail,
                    onValueChange = { tail = it.filter { c -> c.isDigit() } },
                    label = { Text("Tail (ms, default 50)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "CSMA — controls how aggressively this station transmits when the channel is clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = persist,
                    onValueChange = { persist = it.filter { c -> c.isDigit() } },
                    label = { Text("Persistence 0–255 (default 63)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = slotTime,
                    onValueChange = { slotTime = it.filter { c -> c.isDigit() } },
                    label = { Text("Slot time (ms, default 100)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(AfskSettings(
                    txDelayMs    = txDelay.toIntOrNull() ?: -1,
                    preambleFlags = preamble.toIntOrNull()?.coerceIn(1, 200) ?: 20,
                    tailMs       = tail.toIntOrNull()?.coerceAtLeast(0) ?: 50,
                    persist      = persist.toIntOrNull()?.coerceIn(0, 255) ?: 63,
                    slotTimeMs   = slotTime.toIntOrNull()?.coerceAtLeast(10) ?: 100,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
