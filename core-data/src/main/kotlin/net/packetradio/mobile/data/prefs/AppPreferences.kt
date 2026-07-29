package net.packetradio.mobile.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.MailboxPrefs
import net.packetradio.mobile.model.NotifyPrefs
import net.packetradio.mobile.model.UiPrefs

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * The scalar-only half of the desktop's `config.toml` (everything that
 * wasn't `#[serde(skip)]`'d out to its own file): [UiPrefs], [HighlightPrefs]
 * (minus its rules list, which lives in Room), [NotifyPrefs], and
 * [MailboxPrefs] (just the enabled flag — messages live in Room too).
 */
class AppPreferences(private val context: Context) {

    private object Keys {
        val SHOW_MONITOR = booleanPreferencesKey("ui_show_monitor")
        val FONT = stringPreferencesKey("ui_font")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("ui_show_timestamps")
        val DEFAULT_CALL = stringPreferencesKey("ui_default_call")
        val LOCATION = stringPreferencesKey("ui_location")
        val HOME_SERVER = stringPreferencesKey("ui_home_server")
        val QRZ_USERNAME = stringPreferencesKey("ui_qrz_username")
        val QRZ_PASSWORD = stringPreferencesKey("ui_qrz_password")
        val HISTORY_LINES = intPreferencesKey("ui_history_lines")
        val MONITOR_BUFFER_LINES = intPreferencesKey("ui_monitor_buffer_lines")

        val HIGHLIGHT_ENABLED = booleanPreferencesKey("highlight_enabled")
        val CALLSIGN_COLOR = stringPreferencesKey("highlight_callsign_color")
        val KNOWN_CALLSIGN_COLOR = stringPreferencesKey("highlight_known_callsign_color")
        val MY_CALL_COLOR = stringPreferencesKey("highlight_my_call_color")
        val AX25_COMMAND_COLOR = stringPreferencesKey("highlight_ax25_command_color")

        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val MAILBOX_ENABLED = booleanPreferencesKey("mailbox_enabled")
    }

    val uiPrefs: Flow<UiPrefs> = context.dataStore.data.map { prefs ->
        val defaults = UiPrefs()
        UiPrefs(
            showMonitor = prefs[Keys.SHOW_MONITOR] ?: defaults.showMonitor,
            font = prefs[Keys.FONT] ?: defaults.font,
            showTimestamps = prefs[Keys.SHOW_TIMESTAMPS] ?: defaults.showTimestamps,
            defaultCall = prefs[Keys.DEFAULT_CALL] ?: defaults.defaultCall,
            location = prefs[Keys.LOCATION] ?: defaults.location,
            homeServer = prefs[Keys.HOME_SERVER] ?: defaults.homeServer,
            qrzUsername = prefs[Keys.QRZ_USERNAME] ?: defaults.qrzUsername,
            qrzPassword = prefs[Keys.QRZ_PASSWORD] ?: defaults.qrzPassword,
            historyLines = prefs[Keys.HISTORY_LINES] ?: defaults.historyLines,
            monitorBufferLines = prefs[Keys.MONITOR_BUFFER_LINES] ?: defaults.monitorBufferLines,
        )
    }

    suspend fun updateUiPrefs(transform: (UiPrefs) -> UiPrefs) {
        val current = uiPrefs.first()
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOW_MONITOR] = updated.showMonitor
            setOrRemove(prefs, Keys.FONT, updated.font)
            prefs[Keys.SHOW_TIMESTAMPS] = updated.showTimestamps
            setOrRemove(prefs, Keys.DEFAULT_CALL, updated.defaultCall)
            setOrRemove(prefs, Keys.LOCATION, updated.location)
            setOrRemove(prefs, Keys.HOME_SERVER, updated.homeServer)
            setOrRemove(prefs, Keys.QRZ_USERNAME, updated.qrzUsername)
            setOrRemove(prefs, Keys.QRZ_PASSWORD, updated.qrzPassword)
            prefs[Keys.HISTORY_LINES] = updated.historyLines
            prefs[Keys.MONITOR_BUFFER_LINES] = updated.monitorBufferLines
        }
    }

    val highlightPrefs: Flow<HighlightPrefs> = context.dataStore.data.map { prefs ->
        val defaults = HighlightPrefs()
        HighlightPrefs(
            enabled = prefs[Keys.HIGHLIGHT_ENABLED] ?: defaults.enabled,
            callsignColor = prefs[Keys.CALLSIGN_COLOR] ?: defaults.callsignColor,
            knownCallsignColor = prefs[Keys.KNOWN_CALLSIGN_COLOR] ?: defaults.knownCallsignColor,
            myCallColor = prefs[Keys.MY_CALL_COLOR] ?: defaults.myCallColor,
            ax25CommandColor = prefs[Keys.AX25_COMMAND_COLOR] ?: defaults.ax25CommandColor,
        )
    }

    suspend fun updateHighlightPrefs(transform: (HighlightPrefs) -> HighlightPrefs) {
        val current = highlightPrefs.first()
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.HIGHLIGHT_ENABLED] = updated.enabled
            prefs[Keys.CALLSIGN_COLOR] = updated.callsignColor
            prefs[Keys.KNOWN_CALLSIGN_COLOR] = updated.knownCallsignColor
            prefs[Keys.MY_CALL_COLOR] = updated.myCallColor
            prefs[Keys.AX25_COMMAND_COLOR] = updated.ax25CommandColor
        }
    }

    val notifyPrefs: Flow<NotifyPrefs> = context.dataStore.data.map { prefs ->
        NotifyPrefs(enabled = prefs[Keys.NOTIFY_ENABLED] ?: false)
    }

    suspend fun setNotifyEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.NOTIFY_ENABLED] = enabled }
    }

    val mailboxPrefs: Flow<MailboxPrefs> = context.dataStore.data.map { prefs ->
        MailboxPrefs(enabled = prefs[Keys.MAILBOX_ENABLED] ?: false)
    }

    suspend fun setMailboxEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.MAILBOX_ENABLED] = enabled }
    }

    private fun setOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>, value: String?) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }
}
