package net.packetradio.mobile.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live integration test against a **real, running Direwolf instance** —
 * not mocked. Requires Direwolf's AGWPE server reachable at
 * `127.0.0.1:8000` (the same dummy-audio test rig documented in this
 * project's memory: `MYCALL KD3BFP-0`, dummy `ADEVICE0 pulse`). Skips
 * itself (rather than failing) if nothing is listening there, so a normal
 * `./gradlew test` run elsewhere doesn't spuriously fail.
 */
class AgwpeRunnerDirewolfLiveTest {

    @Test
    fun `connects and sees Direwolf's own T echo of a sent unproto frame`() = runBlocking {
        if (!DirewolfAvailability.agwpeReachable()) {
            println("Skipping: no AGWPE server at 127.0.0.1:8000")
            return@runBlocking
        }

        val config = PortConfig.Agwpe(host = "127.0.0.1", port = 8000, radioPort = 0, myCall = "KD3BFP-9")
        val runner = AgwpeRunner(config)
        val commands = Channel<PortCommand>(Channel.UNLIMITED)
        val events = Channel<PortEvent>(Channel.UNLIMITED)

        val job = launch { runner.run(commands, events) }

        assertEquals(PortEvent.PortConnected, withTimeout(5000) { events.receive() })

        val marker = "android-live-test-${System.nanoTime()}"
        commands.send(PortCommand.SendUnproto(dest = "CQ", via = emptyList(), bytes = marker.toByteArray()))

        var sawLocalTxLine = false
        var sawDirewolfEcho = false
        withTimeout(8000) {
            while (!sawDirewolfEcho) {
                val event = events.receive()
                if (event is PortEvent.Monitor) {
                    if (event.line.contains("[unproto TX]") && event.line.contains(marker)) sawLocalTxLine = true
                    if (event.line.contains("[T]") && event.line.contains(marker)) sawDirewolfEcho = true
                }
            }
        }
        assertTrue("expected our own locally-logged [unproto TX] line", sawLocalTxLine)
        assertTrue("expected Direwolf's own [T] transmit-confirmation echo", sawDirewolfEcho)

        commands.send(PortCommand.Disconnect)
        commands.close()
        withTimeout(5000) { job.join() }
    }
}
