@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.ports

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.packetradio.mobile.model.ModemMode
import net.packetradio.mobile.model.PttMethod
import kotlin.math.sqrt

private const val ACTION_USB_PERMISSION = "net.packetradio.mobile.USB_PERMISSION"

/**
 * Dropdown picker for USB serial devices supported by usb-serial-for-android.
 *
 * When a device is selected that doesn't yet have permission, the system
 * permission dialog is shown automatically. The [onDeviceSelected] callback
 * fires only once permission is confirmed (or if the device already had it).
 */
@Composable
fun UsbSerialDevicePicker(
    selectedVendorId: Int?,
    selectedProductId: Int?,
    selectedName: String,
    selectedPortIndex: Int = 0,
    onDeviceSelected: (vendorId: Int, productId: Int, name: String, portIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Always reference the latest callback so the BroadcastReceiver never holds a stale lambda.
    val currentOnDeviceSelected by rememberUpdatedState(onDeviceSelected)

    // Pending permission grant: remember the device and port index while we wait for the dialog.
    var pendingDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var pendingPortIndex by remember { mutableStateOf(0) }

    // Register for USB permission result broadcast for the duration of this composable's lifetime.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = pendingDevice ?: return
                val portIdx = pendingPortIndex
                pendingDevice = null
                if (granted) {
                    currentOnDeviceSelected(
                        device.vendorId, device.productId,
                        device.productName ?: "USB serial",
                        portIdx,
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val drivers = remember<List<UsbSerialDriver>>(expanded) {
        if (expanded) UsbSerialProber.getDefaultProber().findAllDrivers(usbManager) else emptyList()
    }

    val portSuffix = if (selectedPortIndex > 0) " — port $selectedPortIndex" else ""
    val label = when {
        selectedVendorId != null && selectedName.isNotBlank() ->
            "$selectedName$portSuffix (VID 0x${selectedVendorId.toString(16)}, PID 0x${selectedProductId?.toString(16)})"
        selectedVendorId != null ->
            "VID 0x${selectedVendorId.toString(16)}, PID 0x${selectedProductId?.toString(16)}$portSuffix"
        else -> "(select a connected USB serial device)"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("USB serial device (PTT)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (drivers.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No supported USB serial devices connected") },
                    onClick = {},
                    enabled = false,
                )
            }
            for (driver in drivers) {
                val device = driver.device
                val baseName = device.productName ?: "USB serial (VID 0x${device.vendorId.toString(16)})"
                val driverType = driver.javaClass.simpleName.removeSuffix("Driver")
                val multiPort = driver.ports.size > 1
                val portCount = driver.ports.size.coerceAtLeast(1)
                repeat(portCount) { portIdx ->
                    val itemLabel = if (multiPort) "$baseName — port $portIdx  ·  $driverType"
                                    else "$baseName  ·  $driverType"
                    DropdownMenuItem(
                        text = { Text(itemLabel) },
                        onClick = {
                            expanded = false
                            if (usbManager.hasPermission(device)) {
                                onDeviceSelected(device.vendorId, device.productId, baseName, portIdx)
                            } else {
                                pendingDevice = device
                                pendingPortIndex = portIdx
                                // FLAG_MUTABLE required so UsbManager can fill in EXTRA_PERMISSION_GRANTED.
                                val mutabilityFlag =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                                val intent = PendingIntent.getBroadcast(
                                    context, 0,
                                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                                )
                                usbManager.requestPermission(device, intent)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Dropdown picker for USB audio input/output devices.
 *
 * Lists devices of type [AudioDeviceInfo.TYPE_USB_DEVICE] and
 * [AudioDeviceInfo.TYPE_USB_HEADSET] visible to [AudioManager]. USB audio
 * requires no special Android permission — the system grants access as soon
 * as the device is plugged in.
 */
@Composable
fun UsbAudioDevicePicker(
    selectedProductName: String,
    onDeviceSelected: (productName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = remember<List<AudioDeviceInfo>>(expanded) {
        if (expanded) {
            audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
                .filter { info ->
                    info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                }
                .distinctBy { it.productName.toString() }
        } else emptyList()
    }

    val label = selectedProductName.ifBlank { "(auto — any connected USB audio device)" }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("USB audio device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto (any connected USB audio device)") },
                onClick = { onDeviceSelected(""); expanded = false },
            )
            if (devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No USB audio devices detected") },
                    onClick = {},
                    enabled = false,
                )
            }
            for (device in devices) {
                val name = device.productName.toString()
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onDeviceSelected(name); expanded = false },
                )
            }
        }
    }
}

/**
 * Live audio input level meter dialog. Opens the matching USB audio input device
 * and displays a VU meter so the user can calibrate their radio's audio output level.
 * Aim for the green zone (≤ 60%) to avoid overdriving the modem's demodulator.
 */
@Composable
fun AudioLevelMeterDialog(audioProductName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Opening audio device…") }

    LaunchedEffect(Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_ALL).firstOrNull { info ->
            val isUsb = info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            val nameMatch = audioProductName.isBlank() ||
                info.productName.toString().equals(audioProductName, ignoreCase = true)
            isUsb && info.isSource && nameMatch
        }
        if (inputDevice == null) {
            statusText = if (audioProductName.isBlank()) "No USB audio input found"
                         else "\"$audioProductName\" not found as input"
            return@LaunchedEffect
        }
        statusText = "Device: ${inputDevice.productName}"

        val sampleRate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
                .also { it.preferredDevice = inputDevice }
        } catch (_: SecurityException) {
            statusText = "RECORD_AUDIO permission required — grant it in Settings > App permissions"
            return@LaunchedEffect
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            statusText = "AudioRecord init failed — check device and RECORD_AUDIO permission"
            return@LaunchedEffect
        }

        record.startRecording()
        val buf = ShortArray(minBuf)
        try {
            while (isActive) {
                val n = withContext(Dispatchers.IO) { record.read(buf, 0, buf.size) }
                if (n > 0) {
                    var sumSq = 0.0
                    for (i in 0 until n) sumSq += buf[i].toDouble() * buf[i].toDouble()
                    level = (sqrt(sumSq / n) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                record.stop()
                record.release()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Input Level") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 20-segment VU meter: green 0–11, amber 12–15, red 16–19
                val litCount = (level * 20).toInt()
                Row(Modifier.fillMaxWidth()) {
                    repeat(20) { i ->
                        val baseColor = when {
                            i < 12 -> Color(0xFF4CAF50)
                            i < 16 -> Color(0xFFFFA000)
                            else   -> Color(0xFFF44336)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(28.dp)
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i < litCount) baseColor else baseColor.copy(alpha = 0.15f)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Aim for the green zone (below 60%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun ModemMode.label(): String = when (this) {
    ModemMode.BELL_202_1200 -> "1200 baud AFSK (VHF/UHF — APRS, packet)"
    ModemMode.HF_300        -> "300 baud AFSK (HF — QDX, QMX, Winlink)"
}

private fun PttMethod.label(): String = when (this) {
    PttMethod.RTS -> "RTS"
    PttMethod.DTR -> "DTR"
    PttMethod.CAT -> "CAT"
}

@Composable
fun PttMethodPicker(
    selected: PttMethod,
    onSelected: (PttMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("PTT method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (method in PttMethod.entries) {
                DropdownMenuItem(
                    text = { Text(method.label()) },
                    onClick = { onSelected(method); expanded = false },
                )
            }
        }
    }
}

@Composable
fun ModemModePicker(
    selected: ModemMode,
    onSelected: (ModemMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Modem mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (mode in ModemMode.entries) {
                DropdownMenuItem(
                    text = { Text(mode.label()) },
                    onClick = { onSelected(mode); expanded = false },
                )
            }
        }
    }
}
