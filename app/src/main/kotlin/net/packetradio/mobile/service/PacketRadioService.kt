package net.packetradio.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.packetradio.mobile.R
import net.packetradio.mobile.model.PortEvent

/**
 * Keeps port connections and scheduled beacons running independent of any
 * Activity's lifecycle — the one genuinely new architectural piece this app
 * needs that the desktop client never did, since Android will suspend or
 * kill background work aggressively otherwise. Started (so it survives
 * every client unbinding, e.g. during Activity recreation) the moment any
 * port connects, and promotes itself to a foreground service with a
 * low-priority ongoing notification for as long as that's true.
 */
class PacketRadioService : Service() {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    val portManager: PortManager by lazy { PortManager(scope) }
    val beaconScheduler: BeaconScheduler by lazy { BeaconScheduler(scope, portManager) }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: PacketRadioService get() = this@PacketRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Watch our own event stream rather than trusting every call site to
        // remember to call refreshNotification() after a connect/disconnect —
        // found the hard way (a real device test showed the notification
        // stuck on "No ports connected" after a successful connect, because
        // only the disconnect path happened to call it).
        scope.launch {
            portManager.events.collect { envelope ->
                if (envelope.event is PortEvent.PortConnected || envelope.event is PortEvent.PortDisconnected) {
                    refreshNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        beaconScheduler.stopAll()
        portManager.disconnectAll()
        supervisorJob.cancel()
        super.onDestroy()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val connected = portManager.connectedPortIds().size
        val text = if (connected == 0) "No ports connected" else "Connected: $connected port(s)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PGPRC Mobile")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Packet radio connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows while any port is connected or a beacon is scheduled" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "packet_radio_connection"
        private const val NOTIFICATION_ID = 1
    }
}
