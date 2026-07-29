@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Picks from Android's already-*paired* (bonded) Bluetooth devices — this
 * app never scans/pairs itself, it just opens an RFCOMM connection to a
 * device the user already paired via Android's own Bluetooth settings
 * (Mobilinkd/TNC3-style TNCs are paired that way, same as any Bluetooth
 * serial device). `BLUETOOTH_CONNECT` is required at runtime on API 31+ to
 * even read the bonded-device list or a device's name; requested lazily the
 * first time the picker is opened rather than eagerly at app launch, since
 * it's only needed if the user is actually adding a Bluetooth-KISS port.
 */
@Composable
fun BluetoothDevicePicker(
    selectedAddress: String,
    selectedName: String,
    onDeviceSelected: (address: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) expanded = true
    }

    val devices: List<BluetoothDevice> = if (hasPermission) {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    } else {
        emptyList()
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { want ->
            if (hasPermission) {
                expanded = want
            } else if (want) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        },
    ) {
        OutlinedTextField(
            value = if (selectedName.isNotBlank()) "$selectedName ($selectedAddress)" else "(select a paired device)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Paired device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No paired devices — pair one in Android Bluetooth settings first") },
                    onClick = {},
                    enabled = false,
                )
            }
            for (device in devices) {
                DropdownMenuItem(
                    text = { Text("${device.name ?: "(unknown)"} (${device.address})") },
                    onClick = {
                        onDeviceSelected(device.address, device.name ?: device.address)
                        expanded = false
                    },
                )
            }
        }
    }
}
