package net.packetradio.mobile

import android.app.Application
import net.packetradio.mobile.data.PinnedSessionRepository
import net.packetradio.mobile.data.PortRepository
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.prefs.AppPreferences

/** App-wide entry point. [net.packetradio.mobile.service.PacketRadioService] binds independently. */
class PacketRadioApp : Application() {

    val database: PacketRadioDatabase by lazy { PacketRadioDatabase.getInstance(this) }
    val preferences: AppPreferences by lazy { AppPreferences(this) }
    val ports: PortRepository by lazy { PortRepository(database) }
    val pinnedSessions: PinnedSessionRepository by lazy { PinnedSessionRepository(database) }
}
