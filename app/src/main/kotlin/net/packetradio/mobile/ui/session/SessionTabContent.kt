package net.packetradio.mobile.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.defaultHighlightRules

/**
 * A dialed session tab. Identity (node/via/port) is fixed at creation (see
 * [SessionViewModel.dialTab]) and shown read-only here — there's nothing to
 * edit, redialing a different destination means opening a new tab.
 * Word wrap is disabled for the scrollback; the view scrolls both vertically
 * and horizontally to accommodate long BBS menu lines without breaking the column layout.
 */
@Composable
fun SessionTabContent(
    tab: SessionTabState,
    monitorLines: List<MonitorLine>,
    portConnected: Boolean,
    myCall: String,
    highlightPrefs: HighlightPrefs,
    fontSizeSp: Float = 12f,
    onToggleNodeConnection: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    val live = tab.isLive()
    var monitorHeight by remember { mutableStateOf(88.dp) }
    val density = LocalDensity.current
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
    ClearFocusWhenKeyboardHides()

    Column(Modifier.fillMaxSize().padding(12.dp).clearFocusOnTapOutside()) {
        MiniMonitor(
            monitorLines,
            height = monitorHeight,
            myCall = myCall,
            highlightPrefs = highlightPrefs,
            fontSizeSp = fontSizeSp,
            mutedColor = mutedColor,
            errorColor = errorColor,
        )
        MonitorResizeHandle(
            onDrag = { deltaPx ->
                val deltaDp = with(density) { deltaPx.toDp() }
                monitorHeight = (monitorHeight + deltaDp).coerceIn(40.dp, 400.dp)
            },
        )

        if (tab.via.isNotBlank()) {
            Text(
                "Via ${tab.via}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Scrollback — word wrap disabled; scrollable both axes so long BBS lines don't break layout
        val vScrollState = rememberScrollState()
        val hScrollState = rememberScrollState()
        LaunchedEffect(tab.lines.size) {
            vScrollState.animateScrollTo(vScrollState.maxValue)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
                .verticalScroll(vScrollState)
                .horizontalScroll(hScrollState),
        ) {
            for (line in tab.lines) {
                Text(
                    highlightMonitorLine(line, myCall, highlightPrefs, defaultHighlightRules(), mutedColor, errorColor),
                    style = monoStyle,
                    softWrap = false,
                )
            }
        }

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.inputText.isBlank()) {
                val nodeConnected = tab.connectionId != null
                IconButton(onClick = onToggleNodeConnection, enabled = portConnected) {
                    Icon(
                        if (nodeConnected) Icons.Filled.CallEnd else Icons.Filled.Call,
                        contentDescription = if (nodeConnected) "Disconnect node" else "Connect node",
                    )
                }
            }
            OutlinedTextField(
                value = tab.inputText,
                onValueChange = onInputChanged,
                label = { Text("Message") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend, enabled = live) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** An always-visible, user-resizable ([MonitorResizeHandle]) preview of the last few Monitor lines. */
@Composable
private fun MiniMonitor(
    monitorLines: List<MonitorLine>,
    height: Dp,
    myCall: String,
    highlightPrefs: HighlightPrefs,
    fontSizeSp: Float,
    mutedColor: Color,
    errorColor: Color,
) {
    val listState = rememberLazyListState()
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
    LaunchedEffect(monitorLines.size) {
        if (monitorLines.isNotEmpty()) listState.scrollToItem(monitorLines.size - 1)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        LazyColumn(state = listState, modifier = Modifier.padding(6.dp)) {
            items(monitorLines.takeLast(50)) { line ->
                Text(
                    highlightMonitorLine(line.text, myCall, highlightPrefs, defaultHighlightRules(), mutedColor, errorColor),
                    style = monoStyle,
                )
            }
        }
    }
}

/** A drag handle between the mini-monitor and the rest of the tab — lets the user grow/shrink the preview. */
@Composable
private fun MonitorResizeHandle(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}
