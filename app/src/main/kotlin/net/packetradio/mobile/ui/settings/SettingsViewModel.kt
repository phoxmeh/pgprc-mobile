package net.packetradio.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.model.UiPrefs

/** Backs [SettingsScreen] — a thin wrapper over [PacketRadioApp.preferences]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    suspend fun loadCurrent(): UiPrefs = app.preferences.uiPrefs.first()

    fun save(callsign: String, location: String, homeServer: String) {
        viewModelScope.launch {
            app.preferences.updateUiPrefs {
                it.copy(
                    defaultCall = callsign.trim().ifBlank { null },
                    location = location.trim().ifBlank { null },
                    homeServer = homeServer.trim().ifBlank { null },
                )
            }
        }
    }
}
