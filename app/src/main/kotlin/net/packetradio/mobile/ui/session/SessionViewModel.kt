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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PinnedSession
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.supportsUnproto
import net.packetradio.mobile.service.PacketRadioService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

private const val MONITOR_BUFFER_LINES = 5000

/** The Monitor screen's always-available freeform unproto compose bar — see [SessionViewModel.adHoc]. */
data class AdHocUnprotoState(
    val portId: String? = null,
    val node: String = "",
    val via: String = "",
    val inputText: String = "",
)

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

    /** Port connect/disconnect/error and AX.25 connection-state noise — kept out of [monitorLines],
     *  which is packet traffic only, mirroring the desktop's Monitor/Log split. */
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _logFilter = MutableStateFlow("")
    val logFilter: StateFlow<String> = _logFilter.asStateFlow()

    /** The freeform unproto compose surface (Monitor screen) — not tied to any tab, since a tab
     *  is now always a dialed two-way session; this is the only way to use a KISS-only port. */
    private val _adHoc = MutableStateFlow(AdHocUnprotoState())
    val adHoc: StateFlow<AdHocUnprotoState> = _adHoc.asStateFlow()

    private val _portStatuses = MutableStateFlow<Map<String, PortStatus>>(emptyMap())
    val portStatuses: StateFlow<Map<String, PortStatus>> = _portStatuses.asStateFlow()

    val highlightPrefs: StateFlow<HighlightPrefs> = app.preferences.highlightPrefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HighlightPrefs())

    val myCall: StateFlow<String> = app.preferences.uiPrefs.map { it.defaultCall ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // (portId, remote) -> tabId, while a tab's OpenConnection is in flight.
    private val pendingOpens = ConcurrentHashMap<Pair<String, String>, String>()

    // (portId, ConnectionId) -> tabId, once acknowledged.
    private val boundConnections = ConcurrentHashMap<Pair<String, ConnectionId>, String>()

    init {
        viewModelScope.launch {
            app.ports.observeAll().collect { list ->
                _ports.value = list
                if (_adHoc.value.portId == null) {
                    list.firstOrNull { it.config.supportsUnproto() }?.let { port ->
                        _adHoc.update { it.copy(portId = port.id) }
                    }
                }
            }
        }
        // Restores pinned tabs as disconnected shells so they survive this ViewModel (and its
        // in-memory tab list) not surviving a process/task death — see SessionTabState's doc.
        // Runs once per cold ViewModel instance only: a config-change recreation reuses the
        // same instance, so this never duplicates tabs already rehydrated this session.
        viewModelScope.launch {
            val pinned = app.pinnedSessions.getAll()
            if (pinned.isNotEmpty()) {
                _tabs.update { existing ->
                    existing + pinned.map { session ->
                        SessionTabState(
                            id = UUID.randomUUID().toString(),
                            portId = session.portId,
                            node = session.remote,
                            via = session.via,
                            pinned = true,
                        )
                    }
                }
            }
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

    /** Drops every connection and stops the background service — the drawer's "Quit" entry. */
    fun quit() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, PacketRadioService::class.java))
    }

    // --- Tabs ---------------------------------------------------------

    /**
     * Dials a new session tab — the only way a tab is ever created. Identity
     * (`portId`/`node`/`via`) is fixed for this tab's whole lifetime from
     * here on (see [SessionTabState]). `connectImmediately = false` mirrors
     * the desktop's "Open Disconnected": just creates the shell for offline
     * history review, dials nothing.
     */
    fun dialTab(portId: String, node: String, via: String, connectImmediately: Boolean) {
        val tab = SessionTabState(id = UUID.randomUUID().toString(), portId = portId, node = node, via = via)
        _tabs.update { it + tab }
        _selectedTabId.value = tab.id
        if (connectImmediately) {
            pendingOpens[portId to node.trim().uppercase()] = tab.id
            openTabConnection(portId, tab)
        }
    }

    fun closeTab(tabId: String) {
        val closed = _tabs.value.find { it.id == tabId }
        _tabs.update { tabs -> tabs.filterNot { it.id == tabId } }
        if (_selectedTabId.value == tabId) {
            _selectedTabId.value = _tabs.value.firstOrNull()?.id
        }
        // Closing a pinned tab is an explicit "forget this", distinct from unpinning it while
        // keeping the tab open — otherwise it would silently reappear as a shell next launch.
        if (closed != null && closed.pinned && closed.portId != null) {
            viewModelScope.launch { app.pinnedSessions.unpin(closed.toPinnedSession()) }
        }
    }

    fun selectTab(tabId: String) {
        _selectedTabId.value = tabId
    }

    fun setTabInput(tabId: String, text: String) = updateTab(tabId) { it.copy(inputText = text) }

    fun togglePin(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        if (tab.portId == null) return
        val nowPinned = !tab.pinned
        updateTab(tabId) { it.copy(pinned = nowPinned) }
        val session = tab.toPinnedSession()
        viewModelScope.launch {
            if (nowPinned) app.pinnedSessions.pin(session) else app.pinnedSessions.unpin(session)
        }
    }

    private fun SessionTabState.toPinnedSession(): PinnedSession =
        PinnedSession(portId = requireNotNull(portId), remote = node, via = via)

    /**
     * Node-level connect/disconnect only — sends an actual AX.25
     * [PortCommand.OpenConnection]/[PortCommand.CloseConnection] frame over
     * an already-open port, reusing this tab's fixed node/via. Deliberately
     * does *not* touch the port's own connection state; that's
     * [togglePort]'s job. A no-op if the port isn't connected yet (nothing
     * to dial over) — the UI disables the button in that case.
     */
    fun toggleNodeConnection(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val port = ports.value.find { it.id == tab.portId } ?: return
        val svc = service ?: return
        if (!svc.portManager.isConnected(port.id)) return

        val connectionId = tab.connectionId
        if (connectionId != null) {
            viewModelScope.launch { svc.portManager.sendCommand(port.id, PortCommand.CloseConnection(connectionId)) }
        } else {
            pendingOpens[port.id to tab.node.trim().uppercase()] = tabId
            openTabConnection(port.id, tab)
        }
    }

    /** Sends over a tab's live connection only — every tab is a dialed two-way session now, so
     *  there's no unproto fallback here; see [sendAdHoc] for freeform unproto messaging. */
    fun sendTabInput(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val port = ports.value.find { it.id == tab.portId } ?: return
        val svc = service ?: return
        val connectionId = tab.connectionId ?: return
        val text = tab.inputText
        if (text.isBlank()) return
        val bytes = text.toByteArray()

        viewModelScope.launch { svc.portManager.sendCommand(port.id, PortCommand.Send(connectionId, bytes)) }
        updateTab(tabId) {
            it.copy(
                lines = it.lines + "» $text",
                inputText = "",
                packetsSent = it.packetsSent + 1,
                bytesSent = it.bytesSent + bytes.size,
            )
        }
    }

    // --- Ad-hoc unproto (Monitor screen) ---------------------------------

    fun setAdHocPort(portId: String) = _adHoc.update { it.copy(portId = portId) }
    fun setAdHocNode(node: String) = _adHoc.update { it.copy(node = node) }
    fun setAdHocVia(via: String) = _adHoc.update { it.copy(via = via) }
    fun setAdHocInput(text: String) = _adHoc.update { it.copy(inputText = text) }

    fun sendAdHoc() {
        val state = _adHoc.value
        val portId = state.portId ?: return
        val svc = service ?: return
        if (state.node.isBlank() || state.inputText.isBlank()) return
        val via = parseVia(state.via)
        val bytes = state.inputText.toByteArray()
        viewModelScope.launch {
            svc.portManager.sendCommand(portId, PortCommand.SendUnproto(state.node.trim().uppercase(), via, bytes))
        }
        _adHoc.update { it.copy(inputText = "") }
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

    fun setLogFilter(text: String) {
        _logFilter.value = text
    }

    // --- Event routing ---------------------------------------------------

    private fun handleEvent(portId: String, event: PortEvent) {
        when (event) {
            is PortEvent.Monitor -> appendMonitorLine("[${portLabel(portId)}] ${event.line}")
            PortEvent.PortConnected -> {
                _portStatuses.update { it + (portId to PortStatus.CONNECTED) }
                appendLogLine("[${portLabel(portId)}] Port connected")
                firePendingOpensFor(portId)
            }
            is PortEvent.PortDisconnected -> {
                _portStatuses.update { it + (portId to PortStatus.OFF) }
                appendLogLine("[${portLabel(portId)}] Port disconnected")
                clearBoundConnectionsForPort(portId)
            }
            is PortEvent.PortError -> {
                _portStatuses.update { it + (portId to PortStatus.ERROR) }
                appendLogLine("[${portLabel(portId)}] ERROR: ${event.message}")
            }
            is PortEvent.ConnectionOpened -> {
                val tabId = pendingOpens.remove(portId to event.label) ?: return
                boundConnections[portId to event.id] = tabId
                updateTab(tabId) { it.copy(connectionId = event.id) }
            }
            is PortEvent.ConnStateChanged -> {
                val tabId = boundConnections[portId to event.id] ?: return
                val tab = _tabs.value.find { it.id == tabId } ?: return
                appendLogLine("[${portLabel(portId)}] ${tab.node}: ${event.state}")
                val justConnected = event.state == ConnState.CONNECTED && tab.connState != ConnState.CONNECTED
                updateTab(tabId) { t ->
                    val since = when {
                        event.state != ConnState.CONNECTED -> null
                        t.connState == ConnState.CONNECTED -> t.connectedSinceMillis
                        else -> System.currentTimeMillis()
                    }
                    val lines = if (justConnected) t.lines + "— Connected —" else t.lines
                    t.copy(connState = event.state, connectedSinceMillis = since, lines = lines)
                }
            }
            is PortEvent.ConnectionClosed -> {
                val tabId = boundConnections.remove(portId to event.id) ?: return
                updateTab(tabId) { tab ->
                    val line = if (tab.connState == ConnState.CONNECTED) "— Disconnected —" else "— Connection timed out —"
                    tab.copy(
                        connectionId = null,
                        connState = ConnState.DISCONNECTED,
                        connectedSinceMillis = null,
                        lines = tab.lines + line,
                    )
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
        updateTab(tab.id) { it.copy(lines = it.lines + "— Connecting to ${tab.node}… —") }
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
                it.copy(
                    connectionId = null,
                    connState = ConnState.DISCONNECTED,
                    connectedSinceMillis = null,
                    lines = it.lines + "— Port disconnected —",
                )
            }
        }
    }

    /** The same `n` (0-based position in [ports]) used everywhere else — the Ports drawer, tab
     *  titles — rather than the raw Room-generated UUID `portId`, which is meaningless to read. */
    private fun portLabel(portId: String): String {
        val index = _ports.value.indexOfFirst { it.id == portId }
        return if (index >= 0) index.toString() else portId
    }

    private fun appendMonitorLine(line: String) {
        _monitorLines.update { (it + line).takeLast(MONITOR_BUFFER_LINES) }
    }

    private fun appendLogLine(line: String) {
        _logLines.update { (it + line).takeLast(MONITOR_BUFFER_LINES) }
    }

    private fun updateTab(tabId: String, transform: (SessionTabState) -> SessionTabState) {
        _tabs.update { tabs -> tabs.map { if (it.id == tabId) transform(it) else it } }
    }

    private fun parseVia(via: String): List<String> =
        via.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
}
