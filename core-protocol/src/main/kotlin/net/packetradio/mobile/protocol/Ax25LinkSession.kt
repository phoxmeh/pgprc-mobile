package net.packetradio.mobile.protocol

/**
 * AX.25 2.0 (modulo-8, "Class 1") connected-mode link state machine for a single fixed
 * (local, remote, via-path) link — the one piece of the AX.25 spec this app has never
 * implemented client-side (see [Ax25]'s doc comment): the connect/disconnect handshake,
 * I-frame sequencing, and the go-back-N retransmission that a bare KISS TNC has no notion
 * of on its own (unlike AGWPE, which offloads all of this to the host software).
 *
 * A pure reducer by design: [handle] takes one [Event] and returns the [Effect]s to carry
 * out — frames to transmit, timers to (re)start/stop, data to deliver, state transitions to
 * report. It owns no coroutines, no sockets, and schedules no real timers itself; the caller
 * (a `PortRunner`) writes transmitted bytes to the wire, runs the actual T1/T3 delays, and
 * feeds [Event.T1Expired]/[Event.T3Expired] back in when they fire. This split is what makes
 * the whole thing unit-testable without real sockets or timers.
 *
 * Covers the full AX.25 2.0 link layer: connect/disconnect, I-frame sequencing with
 * go-back-N retransmission, RR/RNR/REJ flow control in both directions (including this
 * station's own busy signaling via [Event.SetLocalBusy]), the T3 idle-link poll, and FRMR
 * generation on a protocol error from the peer (invalid/unimplemented control field, an
 * I-field too long or present where not permitted, or an invalid N(R)).
 *
 * Deliberately out of scope, matching this codebase's "keep it real" boundary: SABME/
 * modulo-128 extended sequencing, SREJ selective-reject, and XID parameter negotiation
 * (virtually every TNC and packet BBS in practice speaks plain mod-8 AX.25 2.0).
 */
class Ax25LinkSession(
    private val myCall: Ax25Address,
    private val remoteCall: Ax25Address,
    private val via: List<Ax25Address> = emptyList(),
    private val windowSize: Int = 4,
    private val maxRetries: Int = 10,
    /** Max I-field length before an incoming I-frame is a protocol error (AX.25 N1). */
    private val n1MaxInfoLen: Int = 256,
) {
    enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    sealed interface Event {
        data object UserConnect : Event
        data object UserDisconnect : Event
        data class UserSend(val bytes: ByteArray) : Event

        /**
         * [control] and [hasUnexpectedInfo] are only used for FRMR protocol-error detection
         * (see [frmrCondition]) — default so callers that don't care (most existing tests)
         * can construct this with just [content].
         */
        data class FrameReceived(
            val content: Ax25FrameContent,
            val control: Int = 0,
            val hasUnexpectedInfo: Boolean = false,
        ) : Event

        data object T1Expired : Event

        /** Tells the peer whether we can currently accept more I-frames. */
        data class SetLocalBusy(val busy: Boolean) : Event

        data object T3Expired : Event
    }

    sealed interface Effect {
        data class Transmit(val bytes: ByteArray) : Effect
        data class StateChanged(val state: LinkState, val reason: String? = null) : Effect
        data class DataReceived(val bytes: ByteArray) : Effect
        data object StartT1 : Effect
        data object StopT1 : Effect
        data object StartT3 : Effect
        data object StopT3 : Effect
    }

    private data class Unacked(val seq: Int, val payload: ByteArray)

    /** A locally-detected protocol error worth an FRMR: see AX.25 2.0's W/X/Y/Z conditions. */
    private data class FrmrReason(
        val control: Int,
        val w: Boolean = false,
        val x: Boolean = false,
        val y: Boolean = false,
        val z: Boolean = false,
    )

    var state: LinkState = LinkState.DISCONNECTED
        private set

    private var vs = 0 // V(S) — next sequence number we'll send
    private var va = 0 // V(A) — oldest unacknowledged sequence number
    private var vr = 0 // V(R) — next sequence number we expect to receive
    private val unacked = ArrayDeque<Unacked>()
    private val pendingSend = ArrayDeque<ByteArray>()
    private var peerBusy = false
    private var localBusy = false
    private var retries = 0

    /** Set by [onT3Expired] when we sent a status poll; cleared once the peer proves it's alive. */
    private var pollPending = false

    fun handle(event: Event): List<Effect> = when (event) {
        Event.UserConnect -> onUserConnect()
        Event.UserDisconnect -> onUserDisconnect()
        is Event.UserSend -> onUserSend(event.bytes)
        is Event.FrameReceived -> onFrameReceived(event)
        Event.T1Expired -> onT1Expired()
        is Event.SetLocalBusy -> onSetLocalBusy(event.busy)
        Event.T3Expired -> onT3Expired()
    }

    private fun onUserConnect(): List<Effect> {
        if (state != LinkState.DISCONNECTED) return emptyList()
        resetSequencing()
        retries = 0
        state = LinkState.CONNECTING
        return listOf(Effect.Transmit(sabm()), Effect.StartT1, Effect.StateChanged(state))
    }

    private fun onUserDisconnect(): List<Effect> {
        if (state != LinkState.CONNECTED && state != LinkState.CONNECTING) return emptyList()
        retries = 0
        state = LinkState.DISCONNECTING
        return listOf(Effect.Transmit(disc()), Effect.StartT1, Effect.StopT3, Effect.StateChanged(state))
    }

    private fun onUserSend(bytes: ByteArray): List<Effect> {
        if (state != LinkState.CONNECTED) return emptyList()
        pendingSend.addLast(bytes)
        return flushWindow()
    }

    private fun onFrameReceived(event: Event.FrameReceived): List<Effect> = when (state) {
        LinkState.DISCONNECTED -> onFrameWhileDisconnected(event.content)
        LinkState.CONNECTING -> onFrameWhileConnecting(event.content)
        LinkState.CONNECTED -> onFrameWhileConnected(event.content, event.control, event.hasUnexpectedInfo)
        LinkState.DISCONNECTING -> onFrameWhileDisconnecting(event.content)
    }

    private fun onFrameWhileDisconnected(content: Ax25FrameContent): List<Effect> {
        if (content != Ax25FrameContent.SetAsynchronousBalancedMode) {
            // Anything else addressed to us with no live link: politely say so, per spec.
            return listOf(Effect.Transmit(dm()))
        }
        resetSequencing()
        retries = 0
        state = LinkState.CONNECTED
        return listOf(Effect.Transmit(ua()), Effect.StartT3, Effect.StateChanged(state))
    }

    private fun onFrameWhileConnecting(content: Ax25FrameContent): List<Effect> = when (content) {
        Ax25FrameContent.UnnumberedAcknowledge -> {
            retries = 0
            state = LinkState.CONNECTED
            listOf(Effect.StopT1, Effect.StartT3, Effect.StateChanged(state))
        }
        Ax25FrameContent.DisconnectedMode -> {
            state = LinkState.DISCONNECTED
            listOf(Effect.StopT1, Effect.StateChanged(state, "connection refused"))
        }
        // A simultaneous-connect collision (both sides send SABM at once) is rare enough on a
        // half-duplex link to not be worth its own handling — the next T1 retry resolves it.
        else -> emptyList()
    }

    private fun onFrameWhileDisconnecting(content: Ax25FrameContent): List<Effect> = when (content) {
        Ax25FrameContent.UnnumberedAcknowledge, Ax25FrameContent.DisconnectedMode -> {
            state = LinkState.DISCONNECTED
            listOf(Effect.StopT1, Effect.StopT3, Effect.StateChanged(state))
        }
        else -> emptyList()
    }

    private fun onFrameWhileConnected(content: Ax25FrameContent, control: Int, hasUnexpectedInfo: Boolean): List<Effect> {
        frmrCondition(content, control, hasUnexpectedInfo)?.let { return sendFrmrAndDisconnect(it) }
        pollPending = false

        val effects = mutableListOf<Effect>()
        when (content) {
            is Ax25FrameContent.Information -> {
                effects += ackAndContinue(content.nr)
                if (content.ns == vr) {
                    vr = (vr + 1) % MODULUS
                    if (content.info.isNotEmpty()) effects += Effect.DataReceived(content.info)
                    effects += Effect.Transmit(statusReply(pollFinal = content.pollFinal))
                } else {
                    // Out-of-sequence — go-back-N: ask for a redo starting at what we actually expect.
                    effects += Effect.Transmit(reject(pollFinal = content.pollFinal))
                }
            }
            is Ax25FrameContent.ReceiveReady -> {
                peerBusy = false
                effects += ackAndContinue(content.nr)
                if (content.pollFinal) effects += Effect.Transmit(statusReply(pollFinal = true))
            }
            is Ax25FrameContent.ReceiveNotReady -> {
                peerBusy = true
                effects += ackAndContinue(content.nr)
                if (content.pollFinal) effects += Effect.Transmit(statusReply(pollFinal = true))
            }
            is Ax25FrameContent.Reject -> {
                peerBusy = false
                effects += ackAndRetransmit(content.nr)
                if (content.pollFinal) effects += Effect.Transmit(statusReply(pollFinal = true))
            }
            Ax25FrameContent.SetAsynchronousBalancedMode -> {
                // The peer reset the link from their side (e.g. after their own timeout) — start
                // fresh rather than reject it; we're still the same "connected" link either way.
                resetSequencing()
                retries = 0
                effects += Effect.Transmit(ua())
            }
            Ax25FrameContent.Disconnect -> {
                state = LinkState.DISCONNECTED
                effects += Effect.Transmit(ua())
                effects += Effect.StopT1
                effects += Effect.StopT3
                effects += Effect.StateChanged(state, "remote disconnected")
            }
            Ax25FrameContent.DisconnectedMode -> {
                state = LinkState.DISCONNECTED
                effects += Effect.StopT1
                effects += Effect.StopT3
                effects += Effect.StateChanged(state, "remote reports disconnected")
            }
            is Ax25FrameContent.FrameReject -> {
                state = LinkState.DISCONNECTED
                effects += Effect.StopT1
                effects += Effect.StopT3
                effects += Effect.StateChanged(state, "protocol error (FRMR)")
            }
            is Ax25FrameContent.UnnumberedInformation,
            Ax25FrameContent.UnnumberedAcknowledge,
            is Ax25FrameContent.Unknown,
            -> {
                // A stray UI frame, an unsolicited UA (e.g. a duplicate after we already
                // finished the handshake), or anything unrecognized — none of this link's concern.
                // (Unknown control fields are actually caught by frmrCondition above; this arm
                // only exists for `when` exhaustiveness.)
            }
        }
        if (state == LinkState.CONNECTED) effects += Effect.StartT3
        return effects
    }

    private fun onT1Expired(): List<Effect> {
        retries++
        if (retries > maxRetries) {
            val reason = if (state == LinkState.CONNECTING) "no answer" else "link failure (no response)"
            state = LinkState.DISCONNECTED
            unacked.clear()
            pendingSend.clear()
            pollPending = false
            return listOf(Effect.StopT1, Effect.StopT3, Effect.StateChanged(state, reason))
        }
        return when (state) {
            LinkState.CONNECTING -> listOf(Effect.Transmit(sabm()), Effect.StartT1)
            LinkState.DISCONNECTING -> listOf(Effect.Transmit(disc()), Effect.StartT1)
            LinkState.CONNECTED -> {
                val effects = mutableListOf<Effect>()
                for (u in unacked) effects += Effect.Transmit(information(u.seq, u.payload))
                if (unacked.isEmpty() && pollPending) effects += Effect.Transmit(statusReply(pollFinal = true))
                if (unacked.isNotEmpty() || pollPending) effects += Effect.StartT1
                effects
            }
            LinkState.DISCONNECTED -> emptyList()
        }
    }

    private fun onSetLocalBusy(busy: Boolean): List<Effect> {
        val changed = busy != localBusy
        localBusy = busy
        if (!changed || state != LinkState.CONNECTED) return emptyList()
        // Tell the peer as soon as the condition changes, not just on its next poll.
        return listOf(Effect.Transmit(statusReply(pollFinal = false)))
    }

    private fun onT3Expired(): List<Effect> {
        if (state != LinkState.CONNECTED) return emptyList()
        pollPending = true
        return listOf(Effect.Transmit(statusReply(pollFinal = true)), Effect.StartT1)
    }

    /**
     * The AX.25 2.0 FRMR exception conditions: W (invalid/unimplemented control field), X
     * (I-field too long), Y (I-field present on a frame that doesn't permit one), Z (invalid
     * N(R), i.e. outside our currently outstanding window). `null` means the frame is fine.
     */
    private fun frmrCondition(content: Ax25FrameContent, control: Int, hasUnexpectedInfo: Boolean): FrmrReason? = when (content) {
        is Ax25FrameContent.Unknown -> FrmrReason(control, w = true)
        is Ax25FrameContent.Information -> when {
            content.info.size > n1MaxInfoLen -> FrmrReason(control, x = true)
            !isValidNr(content.nr) -> FrmrReason(control, z = true)
            else -> null
        }
        is Ax25FrameContent.ReceiveReady -> nrOrInfoViolation(control, content.nr, hasUnexpectedInfo)
        is Ax25FrameContent.ReceiveNotReady -> nrOrInfoViolation(control, content.nr, hasUnexpectedInfo)
        is Ax25FrameContent.Reject -> nrOrInfoViolation(control, content.nr, hasUnexpectedInfo)
        Ax25FrameContent.SetAsynchronousBalancedMode,
        Ax25FrameContent.Disconnect,
        Ax25FrameContent.DisconnectedMode,
        Ax25FrameContent.UnnumberedAcknowledge,
        -> if (hasUnexpectedInfo) FrmrReason(control, y = true) else null
        // A peer's own FRMR, and a stray UI frame (its info field is legitimate), aren't errors.
        is Ax25FrameContent.FrameReject, is Ax25FrameContent.UnnumberedInformation -> null
    }

    private fun nrOrInfoViolation(control: Int, nr: Int, hasUnexpectedInfo: Boolean): FrmrReason? = when {
        !isValidNr(nr) -> FrmrReason(control, z = true)
        hasUnexpectedInfo -> FrmrReason(control, y = true)
        else -> null
    }

    /** Whether [nr] falls within our currently outstanding window [V(A), V(S)] (mod 8, inclusive). */
    private fun isValidNr(nr: Int): Boolean {
        var cursor = va
        repeat(MODULUS + 1) {
            if (cursor == nr) return true
            if (cursor == vs) return false
            cursor = (cursor + 1) % MODULUS
        }
        return false
    }

    private fun sendFrmrAndDisconnect(reason: FrmrReason): List<Effect> {
        val frmr = Ax25.encodeFrmr(
            myCall,
            remoteCall,
            via,
            rejectedControl = reason.control,
            vs = vs,
            vr = vr,
            w = reason.w,
            x = reason.x,
            y = reason.y,
            z = reason.z,
        )
        state = LinkState.DISCONNECTED
        unacked.clear()
        pendingSend.clear()
        pollPending = false
        return listOf(Effect.Transmit(frmr), Effect.StopT1, Effect.StopT3, Effect.StateChanged(state, "protocol error (sent FRMR)"))
    }

    /** Sends whatever fits in the window right now; a full window just leaves the rest queued. */
    private fun flushWindow(): List<Effect> {
        val effects = mutableListOf<Effect>()
        var startedT1 = false
        while (!peerBusy && unacked.size < windowSize && pendingSend.isNotEmpty()) {
            val payload = pendingSend.removeFirst()
            unacked.addLast(Unacked(vs, payload))
            effects += Effect.Transmit(information(vs, payload))
            vs = (vs + 1) % MODULUS
            if (!startedT1) {
                effects += Effect.StartT1
                startedT1 = true
            }
        }
        return effects
    }

    /** Drops every frame the peer just acknowledged; false if [nr] doesn't fall in our outstanding window. */
    private fun dropAcked(nr: Int): Boolean {
        var dropped = 0
        var cursor = va
        while (cursor != nr) {
            cursor = (cursor + 1) % MODULUS
            dropped++
            if (dropped > MODULUS) return false
        }
        repeat(dropped.coerceAtMost(unacked.size)) { unacked.removeFirstOrNull() }
        va = nr
        retries = 0
        return true
    }

    /** RR / piggybacked I-frame N(R): just drop what's acked and let the window refill on its own. */
    private fun ackAndContinue(nr: Int): List<Effect> {
        if (!dropAcked(nr)) return emptyList()
        val effects = mutableListOf<Effect>(Effect.StopT1)
        effects += flushWindow()
        if (unacked.isNotEmpty()) effects += Effect.StartT1
        return effects
    }

    /** REJ: drop what's acked, then resend everything still outstanding, in order, before topping up the window. */
    private fun ackAndRetransmit(nr: Int): List<Effect> {
        if (!dropAcked(nr)) return emptyList()
        val effects = mutableListOf<Effect>(Effect.StopT1)
        for (u in unacked) effects += Effect.Transmit(information(u.seq, u.payload))
        if (unacked.isNotEmpty()) effects += Effect.StartT1
        effects += flushWindow()
        return effects
    }

    private fun resetSequencing() {
        vs = 0
        va = 0
        vr = 0
        peerBusy = false
        localBusy = false
        pollPending = false
        unacked.clear()
        pendingSend.clear()
    }

    private fun sabm() = Ax25.encodeSabm(myCall, remoteCall, via)
    private fun disc() = Ax25.encodeDisc(myCall, remoteCall, via)
    private fun ua() = Ax25.encodeUa(myCall, remoteCall, via)
    private fun dm() = Ax25.encodeDm(myCall, remoteCall, via)
    private fun rr(pollFinal: Boolean) = Ax25.encodeReceiveReady(myCall, remoteCall, via, nr = vr, pollFinal = pollFinal, command = false)
    private fun rnr(pollFinal: Boolean) = Ax25.encodeReceiveNotReady(myCall, remoteCall, via, nr = vr, pollFinal = pollFinal, command = false)
    private fun reject(pollFinal: Boolean) = Ax25.encodeReject(myCall, remoteCall, via, nr = vr, pollFinal = pollFinal, command = false)

    /** RR when we can accept more I-frames, RNR when we can't — our own busy state, see [onSetLocalBusy]. */
    private fun statusReply(pollFinal: Boolean) = if (localBusy) rnr(pollFinal) else rr(pollFinal)

    private fun information(ns: Int, payload: ByteArray) =
        Ax25.encodeInformation(myCall, remoteCall, via, ns = ns, nr = vr, info = payload)

    private companion object {
        const val MODULUS = 8
    }
}
