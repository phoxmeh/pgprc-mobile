package net.packetradio.mobile.ui.heard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.AddressBookEntry
import net.packetradio.mobile.model.HeardBeaconPacket

/** Backs [HeardStationsScreen] — a thin wrapper over [PacketRadioApp.addressBook]. */
class HeardStationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    val entries: StateFlow<List<AddressBookEntry>> =
        app.addressBook.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setUserAlias(callsign: String, alias: String) {
        viewModelScope.launch { app.addressBook.setUserAlias(callsign, alias) }
    }

    fun deleteEntry(callsign: String) {
        viewModelScope.launch { app.addressBook.delete(callsign) }
    }
}

/** Backs [HeardStationDetailScreen] for one [callsign]. */
class HeardStationDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    fun entry(callsign: String): StateFlow<AddressBookEntry?> =
        app.addressBook.observeAll()
            .map { list -> list.find { it.callsign == callsign } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun beacons(callsign: String): StateFlow<List<HeardBeaconPacket>> =
        app.addressBook.observeBeacons(callsign)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setUserAlias(callsign: String, alias: String) {
        viewModelScope.launch { app.addressBook.setUserAlias(callsign, alias) }
    }

    fun deleteEntry(callsign: String) {
        viewModelScope.launch { app.addressBook.delete(callsign) }
    }
}
