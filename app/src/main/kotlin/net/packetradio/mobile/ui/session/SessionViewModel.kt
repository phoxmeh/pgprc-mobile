package net.packetradio.mobile.ui.session

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.service.PacketRadioService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

private const val MONITOR_BUFFER_LINES = 5000

/**
 * Owns every open session tab plus the Monitor buffer, binds
 * [PacketRadioService], and correlates its portId-tagged event stream back
 * to the right tab — the direct equivalent of the desktop's `Ui`/`AppState`
 * dispatching `PortEvent`s from N running ports into `handle_event`.
 *
 * Connect-capable ports (AGWPE) correlate by `(portId, remote)` while a
 * connection is pending, then by `(portId, ConnectionId)` once opened —
 * `ConnectionId` is only unique *within* one port (each `PortRunner` mints
 * its own counter from the same base), so the port id must always be part
 * of the key.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    private var service: PacketRadioService? = null
    private var bound = false

    private val _ports = MutableStateFlow<List<PortEntry>>(emptyList())
    val ports: StateFlow<List<PortEntry>> = _ports.asStateFlow()

    private val _tabs = MutableStateFlow<List<SessionTabState>>(emptyList())
    val tabs: StateFlow<List<SessionTabState>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow<String?>(null)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private val _monitorLines = MutableStateFlow<List<String>>(emptyList())
    val monitorLines: StateFlow<List<String>> = _monitorLines.asStateFlow()

    private val _monitorFilter = MutableStateFlow("")
    val monitorFilter: StateFlow<String> = _monitorFilter.asStateFlow()

    private val _portStatuses = MutableStateFlow<Map<String, PortStatus>>(emptyMap())
    val portStatuses: StateFlow<Map<String, PortStatus>> = _portStatuses.asStateFlow()

    // (portId, remote) -> tabId, while a tab's OpenConnection is in flight.
    private val pendingOpens = ConcurrentHashMap<Pair<String, String>, String>()

    // (portId, ConnectionId) -> tabId, once acknowledged.
    private val boundConnections = ConcurrentHashMap<Pair<String, ConnectionId>, String>()

    init {
        viewModelScope.launch {
            app.ports.observeAll().collect { list -> _ports.value = list }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PacketRadioService.LocalBinder).service
            service = svc
            viewModelScope.launch {
                svc.portManager.events.collect { envelope -> handleEvent(envelope.portId, envelope.event) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bindService() {
        if (bound) return
        val context = getApplication<Application>()
        val intent = Intent(context, PacketRadioService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(connection)
            bound = false
        }
        super.onCleared()
    }

    // --- Tabs ---------------------------------------------------------

    fun addTab() {
        val tab = SessionTabState(id = UUID.randomUUID().toString(), portId = ports.value.firstOrNull()?.id)
        _tabs.update { it + tab }
        _selectedTabId.value = tab.id
    }

    fun closeTab(tabId: String) {
        _tabs.update { tabs -> tabs.filterNot { it.id == tabId } }
        if (_selectedTabId.value == tabId) {
            _selectedTabId.value = _tabs.value.firstOrNull()?.id
        }
    }

    fun selectTab(tabId: String) {
        _selectedTabId.value = tabId
    }

    fun setTabPort(tabId: String, portId: String) = updateTab(tabId) { it.copy(portId = portId) }
    fun setTabNode(tabId: String, node: String) = updateTab(tabId) { it.copy(node = node) }
    fun setTabVia(tabId: String, via: String) = updateTab(tabId) { it.copy(via = via) }
    fun setTabUnproto(tabId: String, unproto: Boolean) = updateTab(tabId) { it.copy(unproto = unproto) }
    fun setTabInput(tabId: String, text: String) = updateTab(tabId) { it.copy(inputText = text) }
    fun togglePin(tabId: String) = updateTab(tabId) { it.copy(pinned = !it.pinned) }

    /**
     * Node-level connect/disconnect only — sends an actual AX.25
     * [PortCommand.OpenConnection]/[PortCommand.CloseConnection] frame over
     * an already-open port. Deliberately does *not* touch the port's own
     * connection state; that's [togglePort]'s job. A no-op in Unproto mode
     * (nothing to dial) or if the port isn't connected yet (nothing to dial
     * over) — the UI disables the button in both cases.
     */
    fun toggleNodeConnection(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val port = ports.value.find { it.id == tab.portId } ?: return
        val svc = service ?: return
        if (tab.unproto || !svc.portManager.isConnected(port.id)) return

        val connectionId = tab.connectionId
        if (connectionId != null) {
            viewModelScope.launch { svc.portManager.sendCommand(port.id, PortCommand.CloseConnection(connectionId)) }
        } else if (tab.node.isNotBlank()) {
            pendingOpens[port.id to tab.node.trim().uppercase()] = tabId
            openTabConnection(port.id, tab)
        }
    }

    fun sendTabInput(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val port = ports.value.find { it.id == tab.portId } ?: return
        val svc = service ?: return
        val text = tab.inputText
        if (text.isBlank()) return
        val bytes = text.toByteArray()

        viewModelScope.launch {
            if (tab.connectionId != null) {
                svc.portManager.sendCommand(port.id, PortCommand.Send(tab.connectionId, bytes))
            } else {
                val via = parseVia(tab.via)
                svc.portManager.sendCommand(port.id, PortCommand.SendUnproto(tab.node.trim().uppercase(), via, bytes))
            }
        }
        updateTab(tabId) {
            it.copy(
                lines = it.lines + "» $text",
                inputText = "",
                packetsSent = it.packetsSent + 1,
                bytesSent = it.bytesSent + bytes.size,
            )
        }
    }

    // --- Ports ------------------------------------------------------------

    /** Port-level connect/disconnect only — opens/closes the transport socket, no AX.25 involved. */
    fun togglePort(portId: String) {
        val svc = service ?: return
        val port = ports.value.find { it.id == portId } ?: return
        if (svc.portManager.isConnected(portId)) {
            viewModelScope.launch { svc.portManager.disconnect(portId) }
        } else {
            _portStatuses.update { it - portId } // clear a stale ERROR before retrying
            svc.portManager.connect(portId, port.config)
        }
    }

    fun addPort(name: String, config: PortConfig, autoconnect: Boolean) {
        viewModelScope.launch { app.ports.add(name, config, autoconnect) }
    }

    fun updatePort(entry: PortEntry) {
        viewModelScope.launch { app.ports.update(entry) }
    }

    fun deletePort(portId: String) {
        viewModelScope.launch { app.ports.delete(portId) }
    }

    fun movePortUp(portId: String) {
        viewModelScope.launch { app.ports.moveUp(portId) }
    }

    fun movePortDown(portId: String) {
        viewModelScope.launch { app.ports.moveDown(portId) }
    }

    // --- Monitor --------------------------------------------------------

    fun setMonitorFilter(text: String) {
        _monitorFilter.value = text
    }

    // --- Event routing ---------------------------------------------------

    private fun handleEvent(portId: String, event: PortEvent) {
        when (event) {
            is PortEvent.Monitor -> {
                appendMonitorLine("[$portId] ${event.line}")
                appendUnprotoTrafficToTabs(portId, event)
            }
            PortEvent.PortConnected -> {
                _portStatuses.update { it + (portId to PortStatus.CONNECTED) }
                firePendingOpensFor(portId)
            }
            is PortEvent.PortDisconnected -> {
                _portStatuses.update { it + (portId to PortStatus.OFF) }
                clearBoundConnectionsForPort(portId)
            }
            is PortEvent.PortError -> {
                _portStatuses.update { it + (portId to PortStatus.ERROR) }
                appendMonitorLine("[$portId] ERROR: ${event.message}")
            }
            is PortEvent.ConnectionOpened -> {
                val tabId = pendingOpens.remove(portId to event.label) ?: return
                boundConnections[portId to event.id] = tabId
                updateTab(tabId) { it.copy(connectionId = event.id) }
            }
            is PortEvent.ConnStateChanged -> {
                val tabId = boundConnections[portId to event.id] ?: return
                updateTab(tabId) { tab ->
                    val since = when {
                        event.state != ConnState.CONNECTED -> null
                        tab.connState == ConnState.CONNECTED -> tab.connectedSinceMillis
                        else -> System.currentTimeMillis()
                    }
                    tab.copy(connState = event.state, connectedSinceMillis = since)
                }
            }
            is PortEvent.ConnectionClosed -> {
                val tabId = boundConnections.remove(portId to event.id) ?: return
                updateTab(tabId) {
                    it.copy(connectionId = null, connState = ConnState.DISCONNECTED, connectedSinceMillis = null)
                }
            }
            is PortEvent.Data -> {
                val tabId = boundConnections[portId to event.id] ?: return
                val text = String(event.bytes)
                updateTab(tabId) {
                    it.copy(
                        lines = it.lines + text,
                        packetsReceived = it.packetsReceived + 1,
                        bytesReceived = it.bytesReceived + event.bytes.size,
                    )
                }
            }
            is PortEvent.StationHeard -> {} // address book isn't built yet (a later phase)
        }
    }

    private fun openTabConnection(portId: String, tab: SessionTabState) {
        val svc = service ?: return
        viewModelScope.launch {
            svc.portManager.sendCommand(portId, PortCommand.OpenConnection(tab.node.trim().uppercase(), parseVia(tab.via)))
        }
    }

    private fun firePendingOpensFor(portId: String) {
        val toOpen = pendingOpens.filterKeys { it.first == portId }
        for ((key, tabId) in toOpen) {
            val tab = _tabs.value.find { it.id == tabId } ?: continue
            openTabConnection(portId, tab)
        }
    }

    private fun clearBoundConnectionsForPort(portId: String) {
        val toClear = boundConnections.keys.filter { it.first == portId }
        for (key in toClear) {
            val tabId = boundConnections.remove(key) ?: continue
            updateTab(tabId) {
                it.copy(connectionId = null, connState = ConnState.DISCONNECTED, connectedSinceMillis = null)
            }
        }
    }

    /**
     * Unproto tabs never bind a `ConnectionId` (nothing dials out), so
     * `PortEvent.Data` never fires for them — the only way an incoming reply
     * to a CQ/beacon reaches a tab's own scrollback, instead of only the
     * Monitor buffer, is by mirroring genuinely-directed traffic in here.
     * `Monitor.to` is only ever set for a real received 'U' frame (see
     * AgwpeRunner/KissTcpRunner), never our own TX echo or session traffic,
     * so this can't loop a tab's own sent lines back at itself.
     */
    private fun appendUnprotoTrafficToTabs(portId: String, event: PortEvent.Monitor) {
        val to = event.to?.trim()?.uppercase() ?: return
        _tabs.update { tabs ->
            tabs.map { tab ->
                if (tab.portId == portId && tab.unproto && tab.node.isNotBlank() && tab.node.trim().uppercase() == to) {
                    tab.copy(lines = tab.lines + event.line)
                } else {
                    tab
                }
            }
        }
    }

    private fun appendMonitorLine(line: String) {
        _monitorLines.update { (it + line).takeLast(MONITOR_BUFFER_LINES) }
    }

    private fun updateTab(tabId: String, transform: (SessionTabState) -> SessionTabState) {
        _tabs.update { tabs -> tabs.map { if (it.id == tabId) transform(it) else it } }
    }

    private fun parseVia(via: String): List<String> =
        via.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
}
