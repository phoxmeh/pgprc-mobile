package net.packetradio.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.NetRomNodeEntity
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.data.prefs.AppPreferences
import net.packetradio.mobile.model.NetRomNodeEntry

/**
 * Maintains per-port NET/ROM routing tables populated from NODES broadcasts.
 * Each row tracks a reachable node, the directly-heard gateway to use, the
 * advertised quality, and an obsolescence counter that decrements each broadcast
 * cycle and removes stale entries automatically.
 */
class NetRomRepository(private val db: PacketRadioDatabase, private val prefs: AppPreferences) {

    fun observeAll(): Flow<List<NetRomNodeEntry>> =
        db.netRomNodeDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeForPort(portId: String): Flow<List<NetRomNodeEntry>> =
        db.netRomNodeDao().observeForPort(portId).map { list -> list.map { it.toDomain() } }

    /**
     * Called by [net.packetradio.mobile.service.StationTracker] for every NODES broadcast.
     * [senderCallsign] is the station that sent the broadcast.
     * [neighbors] is the parsed neighbor list: each entry is (nodeCallsign, alias, quality),
     * already filtered to only include entries where the neighbor is directly adjacent to [senderCallsign]
     * (i.e. bestNeighbor == nodeCallsign in the raw broadcast).
     */
    suspend fun processNodeBroadcast(
        portId: String,
        senderCallsign: String,
        neighbors: List<Triple<String, String, Int>>,
    ) {
        val minQuality = prefs.netRomMinQuality.first()
        val initialObsolescence = prefs.netRomInitialObsolescence.first()

        val heardCallsigns = mutableListOf<String>()
        for ((nodeCallsign, alias, quality) in neighbors) {
            if (quality < minQuality) continue
            heardCallsigns.add(nodeCallsign)
            val existing = db.netRomNodeDao().getByPortAndCallsign(portId, nodeCallsign)
            if (existing == null || quality >= existing.quality) {
                db.netRomNodeDao().upsert(
                    NetRomNodeEntity(
                        id = existing?.id ?: 0,
                        portId = portId,
                        callsign = nodeCallsign,
                        alias = alias,
                        bestNeighborCallsign = senderCallsign,
                        quality = quality,
                        obsolescenceCounter = initialObsolescence,
                    ),
                )
            } else {
                // Not better quality — still reset the obsolescence counter since we heard it
                db.netRomNodeDao().update(existing.copy(obsolescenceCounter = initialObsolescence))
            }
        }

        // Decrement obsolescence for nodes on this port that weren't in this broadcast
        if (heardCallsigns.isNotEmpty()) {
            db.netRomNodeDao().decrementObsolescenceExcluding(portId, heardCallsigns)
        } else {
            db.netRomNodeDao().decrementObsolescenceAll(portId)
        }

        db.netRomNodeDao().deleteStale(portId)
    }

    suspend fun clearForPort(portId: String) {
        db.netRomNodeDao().clearForPort(portId)
    }
}
