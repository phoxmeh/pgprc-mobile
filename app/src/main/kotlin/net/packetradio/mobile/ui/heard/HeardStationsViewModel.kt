package net.packetradio.mobile.ui.heard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.CallsignEntry
import net.packetradio.mobile.model.SsidEntry

class HeardStationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    val entries: StateFlow<List<CallsignEntry>> =
        app.addressBook.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteCallsign(baseCallsign: String) {
        viewModelScope.launch { app.addressBook.deleteCallsign(baseCallsign) }
    }

    fun deleteSsid(baseCallsign: String, ssidNumber: Int) {
        viewModelScope.launch { app.addressBook.deleteSsid(baseCallsign, ssidNumber) }
    }

    fun updateCallsign(baseCallsign: String, name: String?, location: String?, notes: String?) {
        viewModelScope.launch { app.addressBook.updateCallsign(baseCallsign, name, location, notes) }
    }

    fun updateSsid(baseCallsign: String, ssidNumber: Int, userAlias: String?, tag: String?, viaPaths: List<String>) {
        viewModelScope.launch { app.addressBook.updateSsid(baseCallsign, ssidNumber, userAlias, tag, viaPaths) }
    }

    fun addSsid(baseCallsign: String, ssidNumber: Int, userAlias: String?, tag: String?, viaPaths: List<String>) {
        viewModelScope.launch { app.addressBook.addSsid(baseCallsign, ssidNumber, userAlias, tag, viaPaths) }
    }

    fun observeBeacons(fullCallsign: String) = app.addressBook.observeBeacons(fullCallsign)

    fun observeBeaconsForBase(baseCallsign: String) = app.addressBook.observeBeaconsForBase(baseCallsign)
}
