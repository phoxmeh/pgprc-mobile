package net.packetradio.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.packetradio.mobile.MainActivity
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
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                portManager.disconnectAll()
                refreshNotification()
                return START_STICKY
            }
            ACTION_QUIT -> {
                // Tells MainActivity (if it's currently alive) to close itself too —
                // stopping the service alone would leave a foregrounded but
                // disconnected app sitting behind, which isn't what "Quit" implies
                // when driven from the notification rather than the in-app drawer.
                sendBroadcast(Intent(ACTION_QUIT).setPackage(packageName))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    // The user swiping this app's card away in the recent-apps/task switcher — as opposed to
    // just backgrounding it with Home or Back, which must leave every port/session running so
    // it keeps streaming off-screen. Only fires for a started (not merely bound) service, and
    // only on an actual task removal, so Home/Back/rotation never reach this.
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
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

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PacketRadioService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quitIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PacketRadioService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PGPRC Mobile")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_notification, "Disconnect", disconnectIntent)
            .addAction(R.drawable.ic_notification, "Quit", quitIntent)
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
        const val ACTION_DISCONNECT = "net.packetradio.mobile.action.DISCONNECT"
        const val ACTION_QUIT = "net.packetradio.mobile.action.QUIT"
    }
}
