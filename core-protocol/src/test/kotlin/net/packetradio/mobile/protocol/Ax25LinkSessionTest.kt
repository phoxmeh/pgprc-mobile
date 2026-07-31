package net.packetradio.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ax25LinkSessionTest {

    private val myCall = Ax25Address("KD3BFP", 9)
    private val remoteCall = Ax25Address("N0CALL", 1)

    private fun session(windowSize: Int = 4, maxRetries: Int = 10) =
        Ax25LinkSession(myCall, remoteCall, windowSize = windowSize, maxRetries = maxRetries)

    private fun List<Ax25LinkSession.Effect>.transmittedContents(): List<Ax25FrameContent> =
        filterIsInstance<Ax25LinkSession.Effect.Transmit>().map { Ax25.decodeFrame(it.bytes)!!.content }

    private fun List<Ax25LinkSession.Effect>.states(): List<Ax25LinkSession.LinkState> =
        filterIsInstance<Ax25LinkSession.Effect.StateChanged>().map { it.state }

    @Test
    fun `user connect sends SABM, starts T1, and reports CONNECTING`() {
        val s = session()
        val effects = s.handle(Ax25LinkSession.Event.UserConnect)

        assertEquals(listOf(Ax25FrameContent.SetAsynchronousBalancedMode), effects.transmittedContents())
        assertTrue(effects.contains(Ax25LinkSession.Effect.StartT1))
        assertEquals(listOf(Ax25LinkSession.LinkState.CONNECTING), effects.states())
        assertEquals(Ax25LinkSession.LinkState.CONNECTING, s.state)
    }

    @Test
    fun `UA while connecting completes the handshake`() {
        val s = session()
        s.handle(Ax25LinkSession.Event.UserConnect)
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.UnnumberedAcknowledge))

        assertTrue(effects.contains(Ax25LinkSession.Effect.StopT1))
        assertEquals(listOf(Ax25LinkSession.LinkState.CONNECTED), effects.states())
        assertEquals(Ax25LinkSession.LinkState.CONNECTED, s.state)
    }

    @Test
    fun `DM while connecting reports connection refused`() {
        val s = session()
        s.handle(Ax25LinkSession.Event.UserConnect)
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.DisconnectedMode))

        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        val stateChanged = effects.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single()
        assertEquals("connection refused", stateChanged.reason)
    }

    @Test
    fun `T1 retries SABM up to the retry limit then gives up`() {
        val s = session(maxRetries = 2)
        s.handle(Ax25LinkSession.Event.UserConnect)

        val retry1 = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(listOf(Ax25FrameContent.SetAsynchronousBalancedMode), retry1.transmittedContents())
        assertEquals(Ax25LinkSession.LinkState.CONNECTING, s.state)

        val retry2 = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(listOf(Ax25FrameContent.SetAsynchronousBalancedMode), retry2.transmittedContents())
        assertEquals(Ax25LinkSession.LinkState.CONNECTING, s.state)

        val giveUp = s.handle(Ax25LinkSession.Event.T1Expired)
        assertTrue(giveUp.transmittedContents().isEmpty())
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals("no answer", giveUp.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single().reason)
    }

    @Test
    fun `incoming SABM while disconnected accepts the link without needing T1`() {
        val s = session()
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.SetAsynchronousBalancedMode))

        assertEquals(listOf(Ax25FrameContent.UnnumberedAcknowledge), effects.transmittedContents())
        assertEquals(listOf(Ax25LinkSession.LinkState.CONNECTED), effects.states())
        assertTrue("accepting an incoming connect shouldn't start a retransmit timer", effects.none { it is Ax25LinkSession.Effect.StartT1 })
    }

    @Test
    fun `a frame arriving with no live link gets a DM`() {
        val s = session()
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveReady(0, false)))
        assertEquals(listOf(Ax25FrameContent.DisconnectedMode), effects.transmittedContents())
    }

    private fun connected(windowSize: Int = 4, maxRetries: Int = 10): Ax25LinkSession {
        val s = session(windowSize, maxRetries)
        s.handle(Ax25LinkSession.Event.UserConnect)
        s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.UnnumberedAcknowledge))
        return s
    }

    @Test
    fun `sending data emits an I-frame with the current N(S) and starts T1`() {
        val s = connected()
        val effects = s.handle(Ax25LinkSession.Event.UserSend("hello".toByteArray()))

        val info = effects.transmittedContents().single() as Ax25FrameContent.Information
        assertEquals(0, info.ns)
        assertEquals("hello", String(info.info))
        assertTrue(effects.contains(Ax25LinkSession.Effect.StartT1))
    }

    @Test
    fun `an RR acknowledging our I-frame stops T1`() {
        val s = connected()
        s.handle(Ax25LinkSession.Event.UserSend("hi".toByteArray()))
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveReady(1, false)))

        assertTrue(effects.contains(Ax25LinkSession.Effect.StopT1))
        assertTrue("nothing left outstanding, so T1 must not restart", effects.none { it is Ax25LinkSession.Effect.StartT1 })
    }

    @Test
    fun `receiving an in-sequence I-frame delivers data and acks it`() {
        val s = connected()
        val effects = s.handle(
            Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Information(ns = 0, nr = 0, pollFinal = false, pid = 0xF0, info = "hi".toByteArray())),
        )

        val data = effects.filterIsInstance<Ax25LinkSession.Effect.DataReceived>().single()
        assertEquals("hi", String(data.bytes))
        val rr = effects.transmittedContents().single() as Ax25FrameContent.ReceiveReady
        assertEquals(1, rr.nr)
    }

    @Test
    fun `an out-of-sequence I-frame is rejected instead of delivered`() {
        val s = connected()
        val effects = s.handle(
            Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Information(ns = 5, nr = 0, pollFinal = false, pid = 0xF0, info = "hi".toByteArray())),
        )

        assertTrue(effects.none { it is Ax25LinkSession.Effect.DataReceived })
        val reject = effects.transmittedContents().single() as Ax25FrameContent.Reject
        assertEquals(0, reject.nr)
    }

    @Test
    fun `REJ retransmits every frame still outstanding, in order`() {
        val s = connected(windowSize = 4)
        s.handle(Ax25LinkSession.Event.UserSend("one".toByteArray()))
        s.handle(Ax25LinkSession.Event.UserSend("two".toByteArray()))

        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Reject(0, false)))
        val resent = effects.transmittedContents().filterIsInstance<Ax25FrameContent.Information>()
        assertEquals(listOf(0, 1), resent.map { it.ns })
        assertEquals(listOf("one", "two"), resent.map { String(it.info) })
    }

    @Test
    fun `RNR pauses new sends until RR clears it`() {
        val s = connected(windowSize = 4)
        s.handle(Ax25LinkSession.Event.UserSend("one".toByteArray()))
        s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveNotReady(0, false)))

        // Queued while busy: nothing new goes out.
        val whileBusy = s.handle(Ax25LinkSession.Event.UserSend("two".toByteArray()))
        assertTrue(whileBusy.transmittedContents().isEmpty())

        // RR(nr=1) acks "one" and clears busy — "two" should now go out as seq 1.
        val resumed = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveReady(1, false)))
        val sent = resumed.transmittedContents().filterIsInstance<Ax25FrameContent.Information>().single()
        assertEquals(1, sent.ns)
        assertEquals("two", String(sent.info))
    }

    @Test
    fun `window size limits outstanding frames, queuing the rest`() {
        val s = connected(windowSize = 2)
        s.handle(Ax25LinkSession.Event.UserSend("a".toByteArray()))
        s.handle(Ax25LinkSession.Event.UserSend("b".toByteArray()))
        val third = s.handle(Ax25LinkSession.Event.UserSend("c".toByteArray()))

        assertTrue("window is full, so a 3rd send must not transmit yet", third.transmittedContents().isEmpty())

        // Acking the first frame opens one slot — "c" should now go out.
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveReady(1, false)))
        val sent = effects.transmittedContents().filterIsInstance<Ax25FrameContent.Information>().single()
        assertEquals("c", String(sent.info))
    }

    @Test
    fun `T1 expiry while connected retransmits unacked I-frames then gives up after max retries`() {
        val s = connected(maxRetries = 1)
        s.handle(Ax25LinkSession.Event.UserSend("hi".toByteArray()))

        val retry = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(listOf(0), retry.transmittedContents().filterIsInstance<Ax25FrameContent.Information>().map { it.ns })

        val giveUp = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals("link failure (no response)", giveUp.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single().reason)
    }

    @Test
    fun `user disconnect sends DISC and completes on UA`() {
        val s = connected()
        val ddisc = s.handle(Ax25LinkSession.Event.UserDisconnect)
        assertEquals(listOf(Ax25FrameContent.Disconnect), ddisc.transmittedContents())
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTING, s.state)

        val done = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.UnnumberedAcknowledge))
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals(listOf(Ax25LinkSession.LinkState.DISCONNECTED), done.states())
    }

    @Test
    fun `remote-initiated disconnect is acknowledged and reported`() {
        val s = connected()
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Disconnect))

        assertEquals(listOf(Ax25FrameContent.UnnumberedAcknowledge), effects.transmittedContents())
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals("remote disconnected", effects.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single().reason)
    }

    @Test
    fun `an unknown control field gets a FRMR and drops the link`() {
        val s = connected()
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Unknown(0x17), control = 0x17))

        val frmr = effects.transmittedContents().single() as Ax25FrameContent.FrameReject
        assertTrue("W condition (invalid/unimplemented control field)", frmr.w)
        assertEquals(0x17, frmr.rejectedControl)
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals("protocol error (sent FRMR)", effects.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single().reason)
    }

    @Test
    fun `an out-of-window N(R) gets a FRMR and drops the link`() {
        val s = connected() // nothing outstanding: V(A) = V(S) = 0, so only N(R) = 0 is valid
        val effects = s.handle(Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.ReceiveReady(nr = 5, pollFinal = false), control = 0x01))

        val frmr = effects.transmittedContents().single() as Ax25FrameContent.FrameReject
        assertTrue("Z condition (invalid N(R))", frmr.z)
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
    }

    @Test
    fun `SetLocalBusy swaps our acks and poll-replies from RR to RNR and back`() {
        val s = connected()

        val busyEffects = s.handle(Ax25LinkSession.Event.SetLocalBusy(true))
        assertEquals(listOf(Ax25FrameContent.ReceiveNotReady(0, false)), busyEffects.transmittedContents())

        val ackWhileBusy = s.handle(
            Ax25LinkSession.Event.FrameReceived(Ax25FrameContent.Information(ns = 0, nr = 0, pollFinal = false, pid = 0xF0, info = "hi".toByteArray())),
        )
        val reply = ackWhileBusy.transmittedContents().single() as Ax25FrameContent.ReceiveNotReady
        assertEquals(1, reply.nr)

        val clearEffects = s.handle(Ax25LinkSession.Event.SetLocalBusy(false))
        assertEquals(listOf(Ax25FrameContent.ReceiveReady(1, false)), clearEffects.transmittedContents())
    }

    @Test
    fun `T3 expiry while idle sends a status poll and supervises it with T1`() {
        val s = connected()
        val effects = s.handle(Ax25LinkSession.Event.T3Expired)

        assertEquals(listOf(Ax25FrameContent.ReceiveReady(0, true)), effects.transmittedContents())
        assertTrue(effects.contains(Ax25LinkSession.Effect.StartT1))
    }

    @Test
    fun `T1 gives up an idle link if the T3 status poll goes unanswered`() {
        val s = connected(maxRetries = 1)
        s.handle(Ax25LinkSession.Event.T3Expired)

        val retry = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(listOf(Ax25FrameContent.ReceiveReady(0, true)), retry.transmittedContents())
        assertEquals(Ax25LinkSession.LinkState.CONNECTED, s.state)

        val giveUp = s.handle(Ax25LinkSession.Event.T1Expired)
        assertEquals(Ax25LinkSession.LinkState.DISCONNECTED, s.state)
        assertEquals("link failure (no response)", giveUp.filterIsInstance<Ax25LinkSession.Effect.StateChanged>().single().reason)
    }
}
