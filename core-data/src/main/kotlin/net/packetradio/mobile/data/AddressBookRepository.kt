package net.packetradio.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.HeardBeaconEntity
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.data.entity.toEntity
import net.packetradio.mobile.model.AddressBookEntry
import net.packetradio.mobile.model.HeardBeaconPacket

/**
 * Domain-level wrapper over the `address_book`/`heard_beacons` tables — the Heard Stations
 * feature's persistence. "Heard count"/"last heard" track every observation, direct or
 * indirect (mentioned in someone else's NET/ROM NODES broadcast); [AddressBookEntry.heardDirectly]
 * separately tracks whether we've ever received an RF frame from the station ourselves, and
 * only ever gets promoted from indirect to direct, never the reverse.
 */
class AddressBookRepository(private val db: PacketRadioDatabase) {

    fun observeAll(): Flow<List<AddressBookEntry>> =
        db.addressBookDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeBeacons(callsign: String): Flow<List<HeardBeaconPacket>> =
        db.heardBeaconDao().observeForCallsign(callsign.uppercase()).map { rows -> rows.map { it.toDomain() } }

    /** A station we directly received an RF frame from (the source of an I/UI/S/U frame). */
    suspend fun recordHeard(callsign: String, viaPath: String = "") = touch(callsign, direct = true, viaPath = viaPath)

    /**
     * The sender of a NET/ROM NODES broadcast (direct) plus every neighbor it lists (indirect
     * unless already known direct). Takes plain (callsign, alias) pairs rather than the
     * core-protocol `NetRomNodesBroadcast` type, since this module only ever deals in
     * core-model types — the caller (`StationTracker`) does that translation.
     */
    suspend fun recordNodeBroadcast(senderCallsign: String, senderAlias: String, neighbors: List<Pair<String, String>>) {
        touch(senderCallsign, direct = true, autoAlias = senderAlias)
        for ((callsign, alias) in neighbors) {
            touch(callsign, direct = false, autoAlias = alias)
        }
    }

    suspend fun setUserAlias(callsign: String, alias: String?) {
        val call = callsign.uppercase()
        val existing = db.addressBookDao().getByCallsign(call) ?: return
        db.addressBookDao().upsert(existing.toDomain().copy(userAlias = alias?.trim()?.ifBlank { null }).toEntity())
    }

    /** Appends to [callsign]'s BEACON log, skipping an exact repeat of the most recent entry and capping at 5. */
    suspend fun recordBeaconPacket(callsign: String, text: String, timestamp: String = nowTimestamp()) {
        val call = callsign.uppercase()
        if (db.heardBeaconDao().mostRecent(call)?.text == text) return
        db.heardBeaconDao().insert(HeardBeaconEntity(callsign = call, text = text, timestamp = timestamp))
        db.heardBeaconDao().pruneOldest(call, keep = 5)
    }

    suspend fun delete(callsign: String) {
        val call = callsign.uppercase()
        val entity = db.addressBookDao().getByCallsign(call) ?: return
        db.addressBookDao().delete(entity)
        db.heardBeaconDao().deleteForCallsign(call)
    }

    private suspend fun touch(callsign: String, direct: Boolean, autoAlias: String? = null, viaPath: String = "") {
        val call = callsign.uppercase()
        val existing = db.addressBookDao().getByCallsign(call)?.toDomain()
        val entry = (existing ?: AddressBookEntry(callsign = call)).copy(
            heardCount = (existing?.heardCount ?: 0) + 1,
            lastHeard = nowTimestamp(),
            heardDirectly = existing?.heardDirectly == true || direct,
            autoAlias = autoAlias?.takeIf { it.isNotBlank() } ?: existing?.autoAlias,
            via = viaPath.ifBlank { existing?.via.orEmpty() },
        )
        db.addressBookDao().upsert(entry.toEntity())
    }
}
