package net.packetradio.mobile.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.transport.PortRunnerFactory

/** One [PortEvent] tagged with which configured port it came from. */
data class PortEventEnvelope(val portId: String, val event: PortEvent)

/**
 * Owns one coroutine job per connected port (each running its `PortRunner`
 * in [scope], so they survive Activity recreation/backgrounding as long as
 * the hosting service does) and funnels every port's events into one
 * app-wide [events] flow tagged by port id — the direct equivalent of the
 * desktop's `Ui`/`AppState` dispatching `PortEvent`s from N running port
 * threads into one `handle_event`.
 */
class PortManager(private val scope: CoroutineScope) {

    /**
     * [connected] only flips true once [PortEvent.PortConnected] actually
     * arrives — an entry existing in [entries] merely means "a connect
     * attempt is in flight", not "the socket is up". Getting this
     * distinction wrong (treating "entry exists" as "connected") let
     * [isConnected] return true before the real TCP connect even
     * completed, found via live-device testing: it caused a caller racing
     * right after [connect] to redundantly fire a second `OpenConnection`.
     */
    private class Entry(val commands: Channel<PortCommand>) {
        @Volatile var connected: Boolean = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _events = MutableSharedFlow<PortEventEnvelope>(extraBufferCapacity = 256)
    val events: SharedFlow<PortEventEnvelope> = _events.asSharedFlow()

    fun connectedPortIds(): Set<String> = entries.filterValues { it.connected }.keys.toSet()
    fun isConnected(portId: String): Boolean = entries[portId]?.connected == true

    /** No-op if [portId] already has a connect attempt in flight or connected. */
    fun connect(portId: String, config: PortConfig) {
        if (entries.containsKey(portId)) return
        val commands = Channel<PortCommand>(Channel.UNLIMITED)
        val portEvents = Channel<PortEvent>(Channel.UNLIMITED)
        val runner = PortRunnerFactory.create(config)
        val entry = Entry(commands)

        entries[portId] = entry

        scope.launch {
            val forwarder = launch {
                for (event in portEvents) {
                    when (event) {
                        PortEvent.PortConnected -> entry.connected = true
                        is PortEvent.PortDisconnected -> entry.connected = false
                        else -> {}
                    }
                    _events.emit(PortEventEnvelope(portId, event))
                }
            }
            // Silent liveness check: a dead USB/Bluetooth/TCP link often surfaces no error at
            // all until something is next written to it — packet radio traffic can otherwise go
            // quiet for very long, legitimate stretches, so idle time alone is never a signal.
            // Runs for as long as the port is connected; the resulting IOException (if any)
            // reaches PortEvent.PortDisconnected via each PortRunner's own catch block.
            val watchdog = launch {
                while (isActive) {
                    delay(PROBE_INTERVAL_MS)
                    if (entry.connected) entry.commands.trySend(PortCommand.Probe)
                }
            }
            try {
                runner.run(commands, portEvents)
            } finally {
                watchdog.cancel()
                portEvents.close()
                forwarder.join()
                entries.remove(portId)
            }
        }
    }

    /** Signals the port to disconnect; it removes itself from [connectedPortIds] once its runner returns. */
    suspend fun disconnect(portId: String) {
        entries[portId]?.commands?.send(PortCommand.Disconnect)
    }

    suspend fun sendCommand(portId: String, command: PortCommand): Boolean {
        val entry = entries[portId] ?: return false
        entry.commands.send(command)
        return true
    }

    /** Best-effort — used when the whole service is going away. */
    fun disconnectAll() {
        entries.values.forEach { it.commands.trySend(PortCommand.Disconnect) }
    }

    private companion object {
        const val PROBE_INTERVAL_MS = 60_000L
    }
}
