package net.packetradio.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.packetradio.mobile.data.dao.AddressBookDao
import net.packetradio.mobile.data.dao.BeaconDao
import net.packetradio.mobile.data.dao.HighlightRuleDao
import net.packetradio.mobile.data.dao.MailboxMessageDao
import net.packetradio.mobile.data.dao.NotifiedPacketDao
import net.packetradio.mobile.data.dao.PinnedSessionDao
import net.packetradio.mobile.data.dao.PortDao
import net.packetradio.mobile.data.dao.QsoLogDao
import net.packetradio.mobile.data.entity.AddressBookEntity
import net.packetradio.mobile.data.entity.BeaconEntity
import net.packetradio.mobile.data.entity.HighlightRuleEntity
import net.packetradio.mobile.data.entity.MailboxMessageEntity
import net.packetradio.mobile.data.entity.NotifiedPacketEntity
import net.packetradio.mobile.data.entity.PinnedSessionEntity
import net.packetradio.mobile.data.entity.PortEntryEntity
import net.packetradio.mobile.data.entity.QsoLogEntryEntity

/**
 * The list-shaped half of the desktop's config split (`ports.toml`,
 * `address_book.toml`, `qso_log.toml`, `notified_packets.toml`, `rules.toml`,
 * `pinned_sessions.toml`, `beacons.toml`, `mailbox.toml`'s `messages`) — the
 * scalar-only half (`UiPrefs`, etc.) lives in [net.packetradio.mobile.data.prefs.AppPreferences]
 * (DataStore) instead, since nothing there is a list.
 */
@Database(
    entities = [
        PortEntryEntity::class,
        AddressBookEntity::class,
        PinnedSessionEntity::class,
        MailboxMessageEntity::class,
        NotifiedPacketEntity::class,
        QsoLogEntryEntity::class,
        BeaconEntity::class,
        HighlightRuleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PacketRadioDatabase : RoomDatabase() {
    abstract fun portDao(): PortDao
    abstract fun addressBookDao(): AddressBookDao
    abstract fun pinnedSessionDao(): PinnedSessionDao
    abstract fun mailboxMessageDao(): MailboxMessageDao
    abstract fun notifiedPacketDao(): NotifiedPacketDao
    abstract fun qsoLogDao(): QsoLogDao
    abstract fun beaconDao(): BeaconDao
    abstract fun highlightRuleDao(): HighlightRuleDao

    companion object {
        @Volatile
        private var instance: PacketRadioDatabase? = null

        fun getInstance(context: Context): PacketRadioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PacketRadioDatabase::class.java,
                    "packet-radio.db",
                ).build().also { instance = it }
            }
    }
}
