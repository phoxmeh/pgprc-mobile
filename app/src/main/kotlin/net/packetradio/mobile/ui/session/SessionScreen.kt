@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.packetradio.mobile.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.ui.ports.PortsDrawerContent

private const val MONITOR_TAB_ID = "__monitor__"
private const val LOG_TAB_ID = "__log__"
private val ConnectedGreen = Color(0xFF2E7D32)
private val DrawerWidth = 300.dp

@Composable
fun SessionScreen(onOpenSettings: () -> Unit, onQuit: () -> Unit, viewModel: SessionViewModel = viewModel()) {
    LaunchedEffect(Unit) { viewModel.bindService() }

    val ports by viewModel.ports.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val selectedTabId by viewModel.selectedTabId.collectAsState()
    val monitorLines by viewModel.monitorLines.collectAsState()
    val monitorFilter by viewModel.monitorFilter.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val logFilter by viewModel.logFilter.collectAsState()
    val adHoc by viewModel.adHoc.collectAsState()
    val portStatuses by viewModel.portStatuses.collectAsState()
    val highlightPrefs by viewModel.highlightPrefs.collectAsState()
    val myCall by viewModel.myCall.collectAsState()

    var leftDrawerOpen by remember { mutableStateOf(false) }
    var rightDrawerOpen by remember { mutableStateOf(false) }
    var showDialDialog by remember { mutableStateOf(false) }
    var filterVisible by remember { mutableStateOf(false) }

    val frontId = selectedTabId ?: MONITOR_TAB_ID
    val frontTab = tabs.find { it.id == frontId }
    val title = when (frontId) {
        MONITOR_TAB_ID -> "Monitor"
        LOG_TAB_ID -> "Log"
        else -> frontTab?.tabName(ports) ?: "PGPRC Mobile"
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { leftDrawerOpen = true; rightDrawerOpen = false }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Sessions")
                        }
                    },
                    title = { Text(title) },
                    actions = {
                        if (frontId == MONITOR_TAB_ID || frontId == LOG_TAB_ID) {
                            IconButton(onClick = { filterVisible = !filterVisible }) {
                                Icon(Icons.Filled.FilterAlt, contentDescription = "Toggle filter")
                            }
                        }
                        IconButton(onClick = { rightDrawerOpen = true; leftDrawerOpen = false }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ports")
                        }
                    },
                )
            },
            bottomBar = { StatusBar(frontTab) },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                when {
                    frontId == MONITOR_TAB_ID -> {
                        ClearFocusWhenKeyboardHides()
                        Column(Modifier.fillMaxSize().padding(12.dp).clearFocusOnTapOutside()) {
                            MonitorContent(
                                monitorLines,
                                monitorFilter,
                                viewModel::setMonitorFilter,
                                myCall,
                                highlightPrefs,
                                showFilter = filterVisible,
                                modifier = Modifier.weight(1f),
                            )
                            AdHocUnprotoBar(
                                ports = ports,
                                state = adHoc,
                                onPortSelected = viewModel::setAdHocPort,
                                onNodeChanged = viewModel::setAdHocNode,
                                onViaChanged = viewModel::setAdHocVia,
                                onInputChanged = viewModel::setAdHocInput,
                                onSend = viewModel::sendAdHoc,
                            )
                        }
                    }
                    frontId == LOG_TAB_ID -> MonitorContent(
                        logLines,
                        logFilter,
                        viewModel::setLogFilter,
                        myCall,
                        highlightPrefs,
                        showFilter = filterVisible,
                    )
                    frontTab != null -> SessionTabContent(
                        tab = frontTab,
                        monitorLines = monitorLines,
                        portConnected = portStatuses[frontTab.portId] == PortStatus.CONNECTED,
                        myCall = myCall,
                        highlightPrefs = highlightPrefs,
                        onToggleNodeConnection = { viewModel.toggleNodeConnection(frontTab.id) },
                        onInputChanged = { viewModel.setTabInput(frontTab.id, it) },
                        onSend = { viewModel.sendTabInput(frontTab.id) },
                    )
                }
            }
        }

        if (leftDrawerOpen) {
            Scrim(onClick = { leftDrawerOpen = false })
        }
        AnimatedVisibility(
            visible = leftDrawerOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
        ) {
            Surface(tonalElevation = 4.dp, modifier = Modifier.width(DrawerWidth).fillMaxHeight().statusBarsPadding()) {
                TabsDrawerContent(
                    tabs = tabs,
                    ports = ports,
                    frontId = frontId,
                    monitorTabId = MONITOR_TAB_ID,
                    logTabId = LOG_TAB_ID,
                    onSelectTab = { viewModel.selectTab(it); leftDrawerOpen = false },
                    onDial = { showDialDialog = true; leftDrawerOpen = false },
                    onCloseTab = viewModel::closeTab,
                    onTogglePin = viewModel::togglePin,
                    onOpenSettings = { leftDrawerOpen = false; onOpenSettings() },
                    onQuit = { viewModel.quit(); onQuit() },
                )
            }
        }

        if (showDialDialog) {
            DialDialog(
                ports = ports,
                onDismiss = { showDialDialog = false },
                onDial = { portId, node, via, connectImmediately ->
                    viewModel.dialTab(portId, node, via, connectImmediately)
                    showDialDialog = false
                },
            )
        }

        if (rightDrawerOpen) {
            Scrim(onClick = { rightDrawerOpen = false })
        }
        AnimatedVisibility(
            visible = rightDrawerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            Surface(tonalElevation = 4.dp, modifier = Modifier.width(DrawerWidth).fillMaxHeight().statusBarsPadding()) {
                PortsDrawerContent(
                    ports = ports,
                    portStatuses = portStatuses,
                    onTogglePort = viewModel::togglePort,
                    onAddPort = viewModel::addPort,
                    onUpdatePort = viewModel::updatePort,
                    onDeletePort = viewModel::deletePort,
                    onMoveUp = viewModel::movePortUp,
                    onMoveDown = viewModel::movePortDown,
                )
            }
        }
    }
}

@Composable
private fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick),
    )
}

/**
 * Reports node-level connection state only — a real AX.25 connect
 * (`connectionId`/`connState == CONNECTED`), timed since it was established.
 * Deliberately unrelated to the underlying port's own connect state (that's
 * the Ports drawer's job).
 */
@Composable
private fun StatusBar(tab: SessionTabState?) {
    val connected = tab != null && tab.connectionId != null && tab.connState == ConnState.CONNECTED
    val label = when {
        tab == null -> "No tab selected"
        connected -> "Connected" + connectedDuration(tab.connectedSinceMillis)?.let { " $it" }.orEmpty()
        tab.connState == ConnState.CONNECTING -> "Connecting…"
        else -> "Disconnected"
    }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) ConnectedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tab != null) {
                Text(
                    "↑${tab.packetsSent} pkt / ${formatBytes(tab.bytesSent)}   ↓${tab.packetsReceived} pkt / ${formatBytes(tab.bytesReceived)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Ticks once a second while [since] is non-null, formatted `mm:ss` (or `h:mm:ss` past an hour). */
@Composable
private fun connectedDuration(since: Long?): String? {
    if (since == null) return null
    var elapsedSeconds by remember(since) { mutableStateOf(0L) }
    LaunchedEffect(since) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - since) / 1000
            delay(1000)
        }
    }
    val h = elapsedSeconds / 3600
    val m = (elapsedSeconds % 3600) / 60
    val s = elapsedSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
