package net.packetradio.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.packetradio.mobile.data.dao.BeaconDao
import net.packetradio.mobile.data.dao.CallsignDao
import net.packetradio.mobile.data.dao.HeardBeaconDao
import net.packetradio.mobile.data.dao.HighlightRuleDao
import net.packetradio.mobile.data.dao.MailboxMessageDao
import net.packetradio.mobile.data.dao.NotifiedPacketDao
import net.packetradio.mobile.data.dao.PinnedSessionDao
import net.packetradio.mobile.data.dao.PortDao
import net.packetradio.mobile.data.dao.QsoLogDao
import net.packetradio.mobile.data.dao.SsidDao
import net.packetradio.mobile.data.dao.NetRomNodeDao
import net.packetradio.mobile.data.dao.WatchedDestinationDao
import net.packetradio.mobile.data.entity.BeaconEntity
import net.packetradio.mobile.data.entity.CallsignEntity
import net.packetradio.mobile.data.entity.HeardBeaconEntity
import net.packetradio.mobile.data.entity.HighlightRuleEntity
import net.packetradio.mobile.data.entity.MailboxMessageEntity
import net.packetradio.mobile.data.entity.NotifiedPacketEntity
import net.packetradio.mobile.data.entity.PinnedSessionEntity
import net.packetradio.mobile.data.entity.PortEntryEntity
import net.packetradio.mobile.data.entity.QsoLogEntryEntity
import net.packetradio.mobile.data.entity.NetRomNodeEntity
import net.packetradio.mobile.data.entity.SsidEntity
import net.packetradio.mobile.data.entity.WatchedDestinationEntity

@Database(
    entities = [
        PortEntryEntity::class,
        CallsignEntity::class,
        SsidEntity::class,
        PinnedSessionEntity::class,
        MailboxMessageEntity::class,
        NotifiedPacketEntity::class,
        QsoLogEntryEntity::class,
        BeaconEntity::class,
        HighlightRuleEntity::class,
        HeardBeaconEntity::class,
        WatchedDestinationEntity::class,
        NetRomNodeEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class PacketRadioDatabase : RoomDatabase() {
    abstract fun portDao(): PortDao
    abstract fun callsignDao(): CallsignDao
    abstract fun ssidDao(): SsidDao
    abstract fun pinnedSessionDao(): PinnedSessionDao
    abstract fun mailboxMessageDao(): MailboxMessageDao
    abstract fun notifiedPacketDao(): NotifiedPacketDao
    abstract fun qsoLogDao(): QsoLogDao
    abstract fun beaconDao(): BeaconDao
    abstract fun highlightRuleDao(): HighlightRuleDao
    abstract fun heardBeaconDao(): HeardBeaconDao
    abstract fun watchedDestinationDao(): WatchedDestinationDao
    abstract fun netRomNodeDao(): NetRomNodeDao

    companion object {
        @Volatile
        private var instance: PacketRadioDatabase? = null

        fun getInstance(context: Context): PacketRadioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PacketRadioDatabase::class.java,
                    "packet-radio.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
