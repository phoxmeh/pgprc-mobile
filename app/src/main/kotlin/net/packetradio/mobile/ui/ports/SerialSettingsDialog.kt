@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.PttMethod
import net.packetradio.mobile.model.SerialFlowControl
import net.packetradio.mobile.model.SerialParity
import net.packetradio.mobile.model.SerialSettings

private val COMMON_BAUD_RATES = listOf(4800, 9600, 19200, 38400, 57600, 115200)

/**
 * Line settings for the radio's PTT/CAT serial port. Most interfaces want the default 9600 8N1;
 * a radio's CAT port often does not, and flow control is only worth turning on when the radio's
 * manual asks for it.
 */
@Composable
fun SerialSettingsDialog(
    settings: SerialSettings,
    pttMethod: PttMethod,
    onSave: (SerialSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var baud by remember { mutableStateOf(settings.baud.toString()) }
    var dataBits by remember { mutableStateOf(settings.dataBits) }
    var stopBits by remember { mutableStateOf(settings.stopBits) }
    var parity by remember { mutableStateOf(settings.parity) }
    var flow by remember { mutableStateOf(settings.flowControl) }

    val baudValue = baud.toIntOrNull()
    val baudValid = baudValue != null && baudValue in 300..2_000_000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Serial settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Used for PTT and CAT on the radio's serial port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baud,
                    onValueChange = { baud = it.filter(Char::isDigit) },
                    label = { Text("Baud rate") },
                    singleLine = true,
                    isError = !baudValid,
                    supportingText = { Text("Common: ${COMMON_BAUD_RATES.joinToString(", ")}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OptionDropdown("Data bits", dataBits, listOf(7, 8), { it.toString() }) { dataBits = it }
                OptionDropdown("Stop bits", stopBits, listOf(1, 2), { it.toString() }) { stopBits = it }
                OptionDropdown("Parity", parity, SerialParity.entries, {
                    when (it) {
                        SerialParity.NONE -> "None"
                        SerialParity.ODD -> "Odd"
                        SerialParity.EVEN -> "Even"
                    }
                }) { parity = it }
                OptionDropdown("Flow control", flow, SerialFlowControl.entries, {
                    when (it) {
                        SerialFlowControl.NONE -> "None"
                        SerialFlowControl.RTS_CTS -> "RTS/CTS (hardware)"
                        SerialFlowControl.XON_XOFF -> "XON/XOFF (software)"
                    }
                }) { flow = it }
                if (flow == SerialFlowControl.RTS_CTS && pttMethod == PttMethod.RTS) {
                    Text(
                        "RTS/CTS flow control uses the RTS line, which RTS PTT also needs — pick a different PTT method or turn flow control off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = baudValid,
                onClick = { onSave(SerialSettings(baudValue!!, dataBits, stopBits, parity, flow)) },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> OptionDropdown(
    label: String,
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}
