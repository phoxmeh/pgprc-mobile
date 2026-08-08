package net.packetradio.mobile.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.packetradio.mobile.PacketRadioApp
import net.packetradio.mobile.data.exportTomlString
import net.packetradio.mobile.data.parseTomlEntries
import net.packetradio.mobile.model.NetRomNodeEntry
import net.packetradio.mobile.model.UiPrefs

/** Backs [SettingsScreen] — a thin wrapper over [PacketRadioApp.preferences]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PacketRadioApp get() = getApplication()

    suspend fun loadCurrent(): UiPrefs = app.preferences.uiPrefs.first()

    fun save(name: String, callsign: String, location: String, homeServer: String) {
        viewModelScope.launch {
            app.preferences.updateUiPrefs {
                it.copy(
                    operatorName = name.trim().ifBlank { null },
                    defaultCall = callsign.trim().ifBlank { null },
                    location = location.trim().ifBlank { null },
                    homeServer = homeServer.trim().ifBlank { null },
                )
            }
        }
    }

    // --- NET/ROM prefs ---

    suspend fun loadNetRomPrefs(): Pair<Int, Int> {
        val minQ = app.preferences.netRomMinQuality.first()
        val initObs = app.preferences.netRomInitialObsolescence.first()
        return minQ to initObs
    }

    fun saveNetRomPrefs(minQuality: Int, initialObsolescence: Int) {
        viewModelScope.launch { app.preferences.saveNetRomPrefs(minQuality, initialObsolescence) }
    }

    val netRomNodes: StateFlow<List<NetRomNodeEntry>> =
        app.netRom.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ports = app.ports.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearNetRomForPort(portId: String) {
        viewModelScope.launch { app.netRom.clearForPort(portId) }
    }

    // --- Address book import / export ---

    suspend fun exportAddressBook(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val entries = app.addressBook.exportAll()
            val toml = exportTomlString(entries)
            app.contentResolver.openOutputStream(uri)?.use { it.write(toml.toByteArray(Charsets.UTF_8)) }
                ?: return@withContext "Export failed: could not open file."
            "Exported ${entries.size} station(s)."
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }

    suspend fun importAddressBook(tomlString: String): String = withContext(Dispatchers.IO) {
        try {
            // Backup first so the user can recover if the import is wrong
            val backupToml = exportTomlString(app.addressBook.exportAll())
            app.filesDir.resolve("address_book_backup.toml").writeText(backupToml, Charsets.UTF_8)
            val entries = parseTomlEntries(tomlString)
            app.addressBook.importFromEntries(entries)
            "Imported ${entries.size} station(s)."
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }
}
