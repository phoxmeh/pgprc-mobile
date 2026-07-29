package net.packetradio.mobile.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.packetradio.mobile.model.Beacon
import net.packetradio.mobile.model.PortCommand

/**
 * One recurring coroutine per *enabled* beacon, direct equivalent of the
 * desktop's `Ui::reschedule_beacons` (`glib::source::timeout_add_seconds_local`
 * timers). Deliberately not WorkManager — its minimum periodic interval
 * (15 minutes) can't express a 15-second beacon.
 */
class BeaconScheduler(private val scope: CoroutineScope, private val portManager: PortManager) {

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Tears down every current timer and starts fresh ones for the given list — call on any edit/save. */
    fun reschedule(beacons: List<Beacon>) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        for (beacon in beacons.filter { it.enabled }) {
            jobs[beacon.id] = scope.launch {
                while (isActive) {
                    delay(beacon.intervalSecs.toLong() * 1000L)
                    if (portManager.isConnected(beacon.portId)) {
                        portManager.sendCommand(
                            beacon.portId,
                            PortCommand.SendUnproto(beacon.dest, parseVia(beacon.via), beacon.message.toByteArray()),
                        )
                    }
                    // Silently skip if the port isn't connected — matches the
                    // desktop's own no-Monitor-spam behavior for a beacon
                    // whose port happens to be offline.
                }
            }
        }
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun parseVia(via: String): List<String> =
        via.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
}
