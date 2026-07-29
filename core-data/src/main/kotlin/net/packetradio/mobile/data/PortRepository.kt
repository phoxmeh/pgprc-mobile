package net.packetradio.mobile.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.data.entity.toEntity
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry

/** Domain-level wrapper over [PacketRadioDatabase]'s port table — the desktop's `ports.toml`. */
class PortRepository(private val db: PacketRadioDatabase) {

    fun observeAll(): Flow<List<PortEntry>> = db.portDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<PortEntry> = db.portDao().getAll().map { it.toDomain() }

    /** Appends a brand-new port at the end of the configured order. */
    suspend fun add(name: String, config: PortConfig, autoconnect: Boolean = false): PortEntry {
        val entry = PortEntry(id = UUID.randomUUID().toString(), name = name, config = config, autoconnect = autoconnect)
        val sortOrder = db.portDao().maxSortOrder() + 1
        db.portDao().upsert(entry.toEntity(sortOrder))
        return entry
    }

    /** Preserves the existing sort position — editing a port never reorders it. */
    suspend fun update(entry: PortEntry) {
        val sortOrder = db.portDao().getById(entry.id)?.sortOrder ?: db.portDao().maxSortOrder() + 1
        db.portDao().upsert(entry.toEntity(sortOrder))
    }

    suspend fun delete(portId: String) = db.portDao().deleteById(portId)

    /** Swaps [id]'s sort position with the entry immediately before it, if any. */
    suspend fun moveUp(id: String) = swapWithNeighbor(id, offset = -1)

    /** Swaps [id]'s sort position with the entry immediately after it, if any. */
    suspend fun moveDown(id: String) = swapWithNeighbor(id, offset = 1)

    private suspend fun swapWithNeighbor(id: String, offset: Int) {
        val ordered = db.portDao().getAll()
        val index = ordered.indexOfFirst { it.id == id }
        val neighborIndex = index + offset
        if (index < 0 || neighborIndex !in ordered.indices) return
        val current = ordered[index]
        val neighbor = ordered[neighborIndex]
        db.portDao().updateSortOrder(current.id, neighbor.sortOrder)
        db.portDao().updateSortOrder(neighbor.id, current.sortOrder)
    }
}
