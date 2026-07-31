package net.packetradio.mobile.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.packetradio.mobile.MainActivity
import net.packetradio.mobile.R
import net.packetradio.mobile.data.AddressBookRepository
import net.packetradio.mobile.data.NotificationRepository
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.protocol.AgwFrame
import net.packetradio.mobile.protocol.decodeNetRomNodes

private const val NETROM_PID = 0xCF
private const val NODES_DEST = "NODES"
private const val BEACON_DEST = "BEACON"

/**
 * Watches every port's traffic (via [PortManager.events], the same app-wide flow
 * [PacketRadioService]'s connection-notification collector already independently subscribes
 * to) to keep the Heard Stations and Notification watch-list features up to date, independent
 * of whether any UI is bound — mirrors [BeaconScheduler]'s lifecycle, owned by
 * [PacketRadioService].
 */
class StationTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val portManager: PortManager,
    private val addressBook: AddressBookRepository,
    private val notifications: NotificationRepository,
) {
    fun start() {
        scope.launch {
            portManager.events.collect { envelope ->
                when (val event = envelope.event) {
                    is PortEvent.StationHeard -> addressBook.recordHeard(event.callsign)
                    is PortEvent.UnprotoReceived -> handleUnproto(envelope.portId, event)
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleUnproto(portId: String, event: PortEvent.UnprotoReceived) {
        if (event.to.equals(NODES_DEST, ignoreCase = true) && event.pid == NETROM_PID) {
            decodeNetRomNodes(event.data)?.let { broadcast ->
                addressBook.recordNodeBroadcast(
                    event.from,
                    broadcast.senderAlias,
                    broadcast.neighbors.map { it.callsign.label() to it.alias },
                )
            }
        }

        if (event.to.equals(BEACON_DEST, ignoreCase = true)) {
            addressBook.recordBeaconPacket(event.from, AgwFrame.textFromBytes(event.data))
        }

        if (notifications.isWatched(event.to)) {
            val text = AgwFrame.textFromBytes(event.data)
            val packet = notifications.record(portId, "${event.from} > ${event.to}: $text")
            postAlert(packet.id, event.to, text)
        }
    }

    private fun postAlert(notifiedPacketId: Long, destination: String, text: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, PacketRadioService.ALERT_CHANNEL_ID)
            .setContentTitle(destination)
            .setContentText(text.take(200))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // The permission may still be denied even though MainActivity requests it at startup —
        // NotificationManagerCompat.notify() already no-ops safely in that case on API 33+, and
        // is a straight passthrough (no permission gate at all) below it.
        NotificationManagerCompat.from(context).takeIf { it.areNotificationsEnabled() }
            ?.notify(ALERT_NOTIFICATION_ID_BASE + (notifiedPacketId % 1000).toInt(), notification)
    }

    companion object {
        private const val ALERT_NOTIFICATION_ID_BASE = 10_000
    }
}
