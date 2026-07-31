package net.packetradio.mobile.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.NotifiedPacketEntity
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.data.entity.toEntity
import net.packetradio.mobile.model.NotifiedPacket
import net.packetradio.mobile.model.WatchedDestination

/** Domain-level wrapper over the `watched_destinations`/`notified_packets` tables. */
class NotificationRepository(private val db: PacketRadioDatabase) {

    fun observeWatchedDestinations(): Flow<List<WatchedDestination>> =
        db.watchedDestinationDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeNotifiedPackets(): Flow<List<NotifiedPacket>> =
        db.notifiedPacketDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun addWatchedDestination(destination: String) {
        val entry = WatchedDestination(id = UUID.randomUUID().toString(), destination = destination.uppercase().trim())
        db.watchedDestinationDao().upsert(entry.toEntity())
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val existing = db.watchedDestinationDao().getById(id) ?: return
        db.watchedDestinationDao().upsert(existing.copy(enabled = enabled))
    }

    suspend fun deleteWatchedDestination(id: String) {
        val existing = db.watchedDestinationDao().getById(id) ?: return
        db.watchedDestinationDao().delete(existing)
    }

    /** True if [destination] matches an enabled watched destination (case-insensitive, exact). */
    suspend fun isWatched(destination: String): Boolean =
        db.watchedDestinationDao().getEnabled().any { it.destination.equals(destination, ignoreCase = true) }

    suspend fun record(portId: String, line: String, timestamp: String = nowTimestamp()): NotifiedPacket {
        val id = db.notifiedPacketDao().insert(NotifiedPacketEntity(portId = portId, line = line, timestamp = timestamp))
        return NotifiedPacket(id = id, portId = portId, line = line, timestamp = timestamp)
    }

    suspend fun delete(packet: NotifiedPacket) = db.notifiedPacketDao().delete(packet.toEntity())
}
