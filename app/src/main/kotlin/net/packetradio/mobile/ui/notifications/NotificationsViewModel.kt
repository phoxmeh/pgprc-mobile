package net.packetradio.mobile.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.NotifiedPacket
import net.packetradio.mobile.model.WatchedDestination

/** Backs [NotificationsScreen] — a thin wrapper over [PacketRadioApp.notifications]. */
class NotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    val watchedDestinations: StateFlow<List<WatchedDestination>> =
        app.notifications.observeWatchedDestinations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifiedPackets: StateFlow<List<NotifiedPacket>> =
        app.notifications.observeNotifiedPackets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addWatchedDestination(destination: String) {
        if (destination.isBlank()) return
        viewModelScope.launch { app.notifications.addWatchedDestination(destination) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { app.notifications.setEnabled(id, enabled) }
    }

    fun deleteWatchedDestination(id: String) {
        viewModelScope.launch { app.notifications.deleteWatchedDestination(id) }
    }
}

/** Backs [NotificationDetailScreen] for one notified-packet [id]. */
class NotificationDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    fun packet(id: Long): StateFlow<NotifiedPacket?> =
        app.notifications.observeNotifiedPackets()
            .map { list -> list.find { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(packet: NotifiedPacket) {
        viewModelScope.launch { app.notifications.delete(packet) }
    }
}
