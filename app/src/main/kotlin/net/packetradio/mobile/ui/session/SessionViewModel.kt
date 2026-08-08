package net.packetradio.mobile.ui.session

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.SsidEntry
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.NetRomNodeEntry
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PinnedSession
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.isTerminalMode
import net.packetradio.mobile.model.supportsUnproto
import net.packetradio.mobile.protocol.NETROM_DEFAULT_TTL
import net.packetradio.mobile.protocol.NetRomL3Packet
import net.packetradio.mobile.protocol.decodeNetRomL3
import net.packetradio.mobile.protocol.encode
import net.packetradio.mobile.service.PacketRadioService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

private const val MONITOR_BUFFER_LINES = 5000
private const val NETROM_PID = 0xCF

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

    /** Feeds the Dial dialog's address-book picker — flat SSID list for autocomplete. */
    val heardStations: StateFlow<List<SsidEntry>> =
        app.addressBook.observeAllSsids().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Feeds the Dial dialog's NET/ROM node autocomplete. */
    val netRomNodes: StateFlow<List<NetRomNodeEntry>> =
        app.netRom.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _tabs = MutableStateFlow<List<SessionTabState>>(emptyList())
    val tabs: StateFlow<List<SessionTabState>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow<String?>(null)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private val _monitorLines = MutableStateFlow<List<MonitorLine>>(emptyList())
    val monitorLines: StateFlow<List<MonitorLine>> = _monitorLines.asStateFlow()

    private val _monitorFilter = MutableStateFlow("")
    val monitorFilter: StateFlow<String> = _monitorFilter.asStateFlow()

    /** Default on: connected-mode chatter (SABM/UA/RR/REJ/I-frames) is mostly debugging noise
     *  next to the UI/unproto traffic the Monitor screen exists to show. */
    private val _unprotoOnly = MutableStateFlow(true)
    val unprotoOnly: StateFlow<Boolean> = _unprotoOnly.asStateFlow()

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

    /** True once preferences load and no callsign has been configured — triggers the first-run dialog. */
    val showFirstRun: StateFlow<Boolean> = app.preferences.uiPrefs.map { it.defaultCall.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Fires once each time a terminal-mode (Telnet) port auto-opens a tab — lets the UI close the Ports drawer. */
    private val _terminalTabOpened = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val terminalTabOpened: SharedFlow<Unit> = _terminalTabOpened.asSharedFlow()

    // (portId, remote) -> tabId, while a tab's OpenConnection is in flight.
    private val pendingOpens = ConcurrentHashMap<Pair<String, String>, String>()

    // (portId, ConnectionId) -> tabId, once acknowledged.
    private val boundConnections = ConcurrentHashMap<Pair<String, ConnectionId>, String>()

    // (portId, myCircuitIndex, myCircuitId) -> tabId, while a NET/ROM ConnectRequest is in flight.
    private val pendingNetRom = ConcurrentHashMap<Triple<String, Int, Int>, String>()

    // (portId, myCircuitIndex, myCircuitId) -> tabId, once ConnectAck received.
    private val boundNetRom = ConcurrentHashMap<Triple<String, Int, Int>, String>()

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
    fun dialTab(portId: String, node: String, via: String, tag: String?, netRomGateway: String?, connectImmediately: Boolean) {
        val myIdx = (1..255).random()
        val myId = (1..255).random()
        val circuit = netRomGateway?.let { gw ->
            NetRomCircuit(myCircuitIndex = myIdx, myCircuitId = myId, gateway = gw)
        }
        val tab = SessionTabState(
            id = UUID.randomUUID().toString(),
            portId = portId,
            node = node.trim().uppercase(),
            via = via,
            tag = tag,
            netRomCircuit = circuit,
        )
        _tabs.update { it + tab }
        _selectedTabId.value = tab.id
        if (connectImmediately) {
            if (circuit != null) {
                pendingNetRom[Triple(portId, myIdx, myId)] = tab.id
                openNetRomConnection(portId, tab, circuit)
            } else {
                pendingOpens[portId to node.trim().uppercase()] = tab.id
                openTabConnection(portId, tab)
            }
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
        // Terminal-mode (Telnet) ports own exactly one session — closing the tab disconnects the port.
        // Pre-clear status to OFF so PortDisconnected doesn't misclassify this as a timeout.
        val closedPortId = closed?.portId ?: return
        val closedPort = _ports.value.find { it.id == closedPortId } ?: return
        if (closedPort.config.isTerminalMode()) {
            _portStatuses.update { it + (closedPortId to PortStatus.OFF) }
            viewModelScope.launch { service?.portManager?.disconnect(closedPortId) }
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

        val circuit = tab.netRomCircuit
        if (circuit != null) {
            // NET/ROM mode — send DisconnectRequest UI frame to gateway
            if (tab.connState == ConnState.CONNECTED || tab.connState == ConnState.CONNECTING) {
                updateTab(tabId) { it.copy(initiatedClose = true) }
                sendNetRomDisconnect(port.id, tab, circuit)
            } else {
                // Not yet connected — just open a new circuit
                val myIdx = (1..255).random()
                val myId = (1..255).random()
                val newCircuit = circuit.copy(myCircuitIndex = myIdx, myCircuitId = myId)
                updateTab(tabId) { it.copy(netRomCircuit = newCircuit) }
                pendingNetRom[Triple(port.id, myIdx, myId)] = tabId
                openNetRomConnection(port.id, tab.copy(netRomCircuit = newCircuit), newCircuit)
            }
        } else {
            val connectionId = tab.connectionId
            if (connectionId != null) {
                updateTab(tabId) { it.copy(initiatedClose = true) }
                viewModelScope.launch { svc.portManager.sendCommand(port.id, PortCommand.CloseConnection(connectionId)) }
            } else {
                pendingOpens[port.id to tab.node.trim().uppercase()] = tabId
                openTabConnection(port.id, tab)
            }
        }
    }

    /** Sends over a tab's live connection only — every tab is a dialed two-way session now, so
     *  there's no unproto fallback here; see [sendAdHoc] for freeform unproto messaging. */
    fun sendTabInput(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val port = ports.value.find { it.id == tab.portId } ?: return
        val svc = service ?: return
        val text = tab.inputText
        if (text.isBlank()) return
        val bytes = (text + "\r").toByteArray()

        val circuit = tab.netRomCircuit
        if (circuit != null) {
            // NET/ROM L3 connected-mode send
            val callsign = myCall.value.ifBlank { return }
            val txSeq = circuit.txSeq
            val packet = NetRomL3Packet.Info(
                origin = callsign,
                destination = tab.node,
                ttl = NETROM_DEFAULT_TTL,
                circuitIndex = circuit.remoteCircuitIndex,
                circuitId = circuit.remoteCircuitId,
                txSeq = txSeq,
                rxSeq = circuit.rxSeq,
                data = bytes,
            )
            val nextTxSeq = (txSeq + 1) and 0x07
            viewModelScope.launch {
                svc.portManager.sendCommand(
                    port.id,
                    PortCommand.SendUnproto(circuit.gateway, emptyList(), packet.encode(), pid = NETROM_PID),
                )
            }
            updateTab(tabId) {
                it.copy(
                    lines = it.lines + "» $text",
                    inputText = "",
                    packetsSent = it.packetsSent + 1,
                    bytesSent = it.bytesSent + bytes.size,
                    netRomCircuit = it.netRomCircuit?.copy(txSeq = nextTxSeq),
                )
            }
        } else {
            // AX.25 connected-mode send
            val connectionId = tab.connectionId ?: return
            // Packet BBS/node command parsers only act on a line once they see its trailing CR;
            // without it, a sent command just sits in their input buffer forever, acked at the AX.25
            // layer (a normal RR) but never actually processed.
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
        if (state.node.isBlank()) return
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
            // Pre-clear to OFF so the incoming PortDisconnected event doesn't see CONNECTED and
            // misclassify this user-initiated disconnect as an unexpected timeout.
            _portStatuses.update { it + (portId to PortStatus.OFF) }
            viewModelScope.launch { svc.portManager.disconnect(portId) }
        } else {
            _portStatuses.update { it + (portId to PortStatus.CONNECTING) }
            svc.portManager.connect(portId, port.config)
        }
    }

    /** Clears a sticky ERROR or TIMEOUT status back to OFF (called from a long-press on the port row). */
    fun clearPortStatus(portId: String) {
        _portStatuses.update { it + (portId to PortStatus.OFF) }
    }

    fun saveMyCall(call: String) {
        val trimmed = call.trim().uppercase().ifBlank { null }
        viewModelScope.launch { app.preferences.updateUiPrefs { it.copy(defaultCall = trimmed) } }
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

    fun setUnprotoOnly(value: Boolean) {
        _unprotoOnly.value = value
    }

    // --- Event routing ---------------------------------------------------

    private fun handleEvent(portId: String, event: PortEvent) {
        when (event) {
            is PortEvent.Monitor -> appendMonitorLine(
                MonitorLine("[${portLabel(portId)}] ${event.line}", unproto = event.to != null),
            )
            PortEvent.PortConnected -> {
                _portStatuses.update { it + (portId to PortStatus.CONNECTED) }
                appendLogLine("[${portLabel(portId)}] Port connected")
                // Terminal-mode ports (Telnet) represent a single direct session — auto-open a tab
                // immediately on connect rather than requiring the user to dial a node callsign.
                val port = _ports.value.find { it.id == portId }
                if (port?.config?.isTerminalMode() == true) {
                    val existing = _tabs.value.find { it.portId == portId && it.connectionId == null }
                    val tab = existing ?: SessionTabState(
                        id = UUID.randomUUID().toString(),
                        portId = portId,
                        node = port.name.trim().uppercase(),
                    )
                    if (existing == null) _tabs.update { it + tab }
                    _selectedTabId.value = tab.id
                    pendingOpens[portId to tab.node.trim().uppercase()] = tab.id
                    _terminalTabOpened.tryEmit(Unit)
                }
                firePendingOpensFor(portId)
            }
            is PortEvent.PortDisconnected -> {
                val currentStatus = _portStatuses.value[portId]
                val newStatus = when {
                    currentStatus == PortStatus.CONNECTED -> PortStatus.TIMEOUT
                    currentStatus == PortStatus.ERROR -> PortStatus.ERROR // keep visible until user clears
                    else -> PortStatus.OFF
                }
                _portStatuses.update { it + (portId to newStatus) }
                val suffix = event.reason?.let { ": $it" }.orEmpty()
                appendLogLine("[${portLabel(portId)}] Port disconnected$suffix")
                clearBoundConnectionsForPort(portId)
                clearNetRomCircuitsForPort(portId)
            }
            is PortEvent.PortError -> {
                _portStatuses.update { it + (portId to PortStatus.ERROR) }
                appendLogLine("[${portLabel(portId)}] ERROR: ${event.message}")
            }
            is PortEvent.PortLog -> appendLogLine("[${portLabel(portId)}] ${event.message}")
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
                    t.copy(
                        connState = event.state,
                        connectedSinceMillis = since,
                        lines = lines,
                        lastLineComplete = if (justConnected) true else t.lastLineComplete,
                        wasConnected = t.wasConnected || event.state == ConnState.CONNECTED,
                    )
                }
            }
            is PortEvent.ConnectionClosed -> {
                val tabId = boundConnections.remove(portId to event.id) ?: return
                updateTab(tabId) { tab ->
                    val line = when {
                        tab.initiatedClose -> "— Disconnected —"
                        // connState is still CONNECTED when the transport dies without a DISC exchange.
                        tab.connState == ConnState.CONNECTED -> "— Connection timed out —"
                        // connState was set to DISCONNECTED by a prior ConnStateChanged event (clean DISC).
                        tab.wasConnected -> "— Disconnected —"
                        else -> "— Connection failed —"
                    }
                    tab.copy(
                        connectionId = null,
                        connState = ConnState.DISCONNECTED,
                        connectedSinceMillis = null,
                        initiatedClose = false,
                        wasConnected = false,
                        lines = tab.lines + line,
                        lastLineComplete = true,
                    )
                }
            }
            is PortEvent.Data -> {
                val tabId = boundConnections[portId to event.id] ?: return
                val text = String(event.bytes)
                // Split on any line ending. If the text ends with a newline the last split element
                // is empty — that empty sentinel is dropped but its presence tells us the last
                // displayed line is now complete (the next packet starts fresh on a new line).
                // If there's no trailing newline the last segment is partial: the next packet's
                // first chunk should be concatenated onto it rather than starting a new line.
                // This matches how real BBS/node software streams long menus across multiple frames.
                val segments = text.split(Regex("\r\n|\r|\n"))
                val endsWithNewline = segments.lastOrNull() == ""
                val cleanSegments = if (endsWithNewline) segments.dropLast(1) else segments
                updateTab(tabId) { tab ->
                    val newLines = when {
                        cleanSegments.isEmpty() -> tab.lines
                        !tab.lastLineComplete && tab.lines.isNotEmpty() ->
                            tab.lines.dropLast(1) +
                                (tab.lines.last() + cleanSegments.first()) +
                                cleanSegments.drop(1)
                        else -> tab.lines + cleanSegments
                    }
                    tab.copy(
                        lines = newLines,
                        lastLineComplete = endsWithNewline,
                        packetsReceived = tab.packetsReceived + 1,
                        bytesReceived = tab.bytesReceived + event.bytes.size,
                    )
                }
            }
            // Persisted by StationTracker (a service-level collector on the same events flow,
            // independent of any bound UI) rather than here — see PacketRadioService.
            is PortEvent.StationHeard -> {}
            is PortEvent.UnprotoReceived -> {
                if (event.pid == NETROM_PID) handleNetRomUnproto(portId, event)
            }
        }
    }

    private fun handleNetRomUnproto(portId: String, event: PortEvent.UnprotoReceived) {
        val packet = decodeNetRomL3(event.data) ?: return
        when (packet) {
            is NetRomL3Packet.ConnectAck -> {
                val key = Triple(portId, packet.circuitIndex, packet.circuitId)
                val tabId = pendingNetRom.remove(key) ?: return
                boundNetRom[key] = tabId
                val newCircuit = NetRomCircuit(
                    myCircuitIndex = packet.circuitIndex,
                    myCircuitId = packet.circuitId,
                    remoteCircuitIndex = packet.remoteCircuitIndex,
                    remoteCircuitId = packet.remoteCircuitId,
                    gateway = event.from,
                    windowSize = packet.acceptedWindowSize,
                )
                updateTab(tabId) { tab ->
                    tab.copy(
                        connState = ConnState.CONNECTED,
                        connectedSinceMillis = System.currentTimeMillis(),
                        netRomCircuit = newCircuit,
                        wasConnected = true,
                        lines = tab.lines + "— Connected (NET/ROM) —",
                        lastLineComplete = true,
                    )
                }
                appendLogLine("[${portLabel(portId)}] NET/ROM connect ACK from ${packet.origin}")
            }
            is NetRomL3Packet.Info -> {
                val key = Triple(portId, packet.circuitIndex, packet.circuitId)
                val tabId = boundNetRom[key] ?: return
                val svc = service ?: return
                val tab = _tabs.value.find { it.id == tabId } ?: return
                val circuit = tab.netRomCircuit ?: return
                // Send INFO ACK
                val callsign = myCall.value.ifBlank { return }
                val newRxSeq = (packet.txSeq + 1) and 0x07
                val ack = NetRomL3Packet.InfoAck(
                    origin = callsign,
                    destination = packet.origin,
                    ttl = NETROM_DEFAULT_TTL,
                    circuitIndex = circuit.remoteCircuitIndex,
                    circuitId = circuit.remoteCircuitId,
                    rxSeq = newRxSeq,
                )
                viewModelScope.launch {
                    svc.portManager.sendCommand(
                        portId,
                        PortCommand.SendUnproto(circuit.gateway, emptyList(), ack.encode(), pid = NETROM_PID),
                    )
                }
                // Route payload to tab
                val text = String(packet.data)
                val segments = text.split(Regex("\r\n|\r|\n"))
                val endsWithNewline = segments.lastOrNull() == ""
                val cleanSegments = if (endsWithNewline) segments.dropLast(1) else segments
                updateTab(tabId) { t ->
                    val newLines = when {
                        cleanSegments.isEmpty() -> t.lines
                        !t.lastLineComplete && t.lines.isNotEmpty() ->
                            t.lines.dropLast(1) + (t.lines.last() + cleanSegments.first()) + cleanSegments.drop(1)
                        else -> t.lines + cleanSegments
                    }
                    t.copy(
                        lines = newLines,
                        lastLineComplete = endsWithNewline,
                        packetsReceived = t.packetsReceived + 1,
                        bytesReceived = t.bytesReceived + packet.data.size,
                        netRomCircuit = t.netRomCircuit?.copy(rxSeq = newRxSeq),
                    )
                }
            }
            is NetRomL3Packet.DisconnectRequest -> {
                val key = Triple(portId, packet.circuitIndex, packet.circuitId)
                val tabId = boundNetRom.remove(key) ?: return
                val svc = service ?: return
                val tab = _tabs.value.find { it.id == tabId } ?: return
                val circuit = tab.netRomCircuit ?: return
                val callsign = myCall.value.ifBlank { return }
                val ack = NetRomL3Packet.DisconnectAck(
                    origin = callsign,
                    destination = packet.origin,
                    ttl = NETROM_DEFAULT_TTL,
                    circuitIndex = circuit.remoteCircuitIndex,
                    circuitId = circuit.remoteCircuitId,
                )
                viewModelScope.launch {
                    svc.portManager.sendCommand(
                        portId,
                        PortCommand.SendUnproto(circuit.gateway, emptyList(), ack.encode(), pid = NETROM_PID),
                    )
                }
                closeNetRomCircuit(tabId, graceful = true)
            }
            is NetRomL3Packet.DisconnectAck -> {
                val key = Triple(portId, packet.circuitIndex, packet.circuitId)
                val tabId = boundNetRom.remove(key) ?: pendingNetRom.remove(key) ?: return
                closeNetRomCircuit(tabId, graceful = true)
            }
            else -> {} // ConnectRequest from remote — not a terminal, ignore
        }
    }

    private fun openNetRomConnection(portId: String, tab: SessionTabState, circuit: NetRomCircuit) {
        val svc = service ?: return
        val callsign = myCall.value.ifBlank { return }
        updateTab(tab.id) { it.copy(lines = it.lines + "— Connecting to ${tab.node} via NET/ROM (${circuit.gateway})… —", wasConnected = false, lastLineComplete = true, connState = ConnState.CONNECTING) }
        val packet = NetRomL3Packet.ConnectRequest(
            origin = callsign,
            destination = tab.node,
            circuitIndex = circuit.myCircuitIndex,
            circuitId = circuit.myCircuitId,
        )
        viewModelScope.launch {
            svc.portManager.sendCommand(portId, PortCommand.SendUnproto(circuit.gateway, emptyList(), packet.encode(), pid = NETROM_PID))
        }
    }

    private fun sendNetRomDisconnect(portId: String, tab: SessionTabState, circuit: NetRomCircuit) {
        val svc = service ?: return
        val callsign = myCall.value.ifBlank { return }
        val packet = NetRomL3Packet.DisconnectRequest(
            origin = callsign,
            destination = tab.node,
            ttl = NETROM_DEFAULT_TTL,
            circuitIndex = circuit.remoteCircuitIndex,
            circuitId = circuit.remoteCircuitId,
        )
        viewModelScope.launch {
            svc.portManager.sendCommand(portId, PortCommand.SendUnproto(circuit.gateway, emptyList(), packet.encode(), pid = NETROM_PID))
        }
    }

    private fun closeNetRomCircuit(tabId: String, graceful: Boolean) {
        updateTab(tabId) { tab ->
            val line = when {
                graceful && tab.initiatedClose -> "— Disconnected —"
                graceful -> "— Remote disconnected —"
                tab.wasConnected -> "— Connection timed out —"
                else -> "— Connection failed —"
            }
            tab.copy(
                connState = ConnState.DISCONNECTED,
                connectedSinceMillis = null,
                initiatedClose = false,
                wasConnected = false,
                lines = tab.lines + line,
                lastLineComplete = true,
            )
        }
    }

    private fun clearNetRomCircuitsForPort(portId: String) {
        val pending = pendingNetRom.keys.filter { it.first == portId }
        for (key in pending) {
            val tabId = pendingNetRom.remove(key) ?: continue
            closeNetRomCircuit(tabId, graceful = false)
        }
        val bound = boundNetRom.keys.filter { it.first == portId }
        for (key in bound) {
            val tabId = boundNetRom.remove(key) ?: continue
            closeNetRomCircuit(tabId, graceful = false)
        }
    }

    private fun openTabConnection(portId: String, tab: SessionTabState) {
        val svc = service ?: return
        updateTab(tab.id) { it.copy(lines = it.lines + "— Connecting to ${tab.node}… —", wasConnected = false, lastLineComplete = true) }
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
                    lastLineComplete = true,
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

    private fun appendMonitorLine(line: MonitorLine) {
        _monitorLines.update { (it + line).takeLast(MONITOR_BUFFER_LINES) }
    }

    private fun appendLogLine(line: String) {
        _logLines.update { (it + line).takeLast(MONITOR_BUFFER_LINES) }
    }

    private fun updateTab(tabId: String, transform: (SessionTabState) -> SessionTabState) {
        _tabs.update { tabs -> tabs.map { if (it.id == tabId) transform(it) else it } }
    }

    private fun parseVia(via: String): List<String> =
        via.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}
