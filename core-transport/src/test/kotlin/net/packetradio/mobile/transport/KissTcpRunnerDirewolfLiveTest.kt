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
 * requires its KISS-TCP server reachable at `127.0.0.1:8001`. Skips itself
 * if nothing is listening there.
 *
 * Unlike AGWPE, a bare KISS TNC never echoes our own transmission back, so
 * this can only verify what the runner itself reports (connect succeeds,
 * the write doesn't error, our own locally-logged TX line appears) — not
 * that Direwolf decoded the AX.25 frame correctly. That's cross-checked
 * separately against Direwolf's own log output, same as this project's
 * established verification method.
 */
class KissTcpRunnerDirewolfLiveTest {

    @Test
    fun `connects to a live Direwolf KISS-TCP server and sends an unproto frame without error`() = runBlocking {
        if (!DirewolfAvailability.kissTcpReachable()) {
            println("Skipping: no KISS-TCP server at 127.0.0.1:8001")
            return@runBlocking
        }

        val config = PortConfig.KissTcp(host = "127.0.0.1", port = 8001, myCall = "KD3BFP-9")
        val runner = KissTcpRunner(config)
        val commands = Channel<PortCommand>(Channel.UNLIMITED)
        val events = Channel<PortEvent>(Channel.UNLIMITED)

        val job = launch { runner.run(commands, events) }

        assertEquals(PortEvent.PortConnected, withTimeout(5000) { events.receive() })

        val marker = "android-kiss-live-test-${System.nanoTime()}"
        commands.send(
            PortCommand.SendUnproto(dest = "CQ", via = listOf("WIDE1-1"), bytes = marker.toByteArray()),
        )

        var sawLocalTxLine = false
        withTimeout(5000) {
            while (!sawLocalTxLine) {
                val event = events.receive()
                if (event is PortEvent.Monitor && event.line.contains("[unproto TX]") && event.line.contains(marker)) {
                    sawLocalTxLine = true
                    assertTrue("expected the via path in the local TX line", event.line.contains("via WIDE1-1"))
                }
            }
        }

        commands.send(PortCommand.Disconnect)
        commands.close()
        withTimeout(5000) { job.join() }

        println("Sent marker '$marker' — cross-check core-transport/../direwolf.log for a matching decoded frame.")
    }
}
