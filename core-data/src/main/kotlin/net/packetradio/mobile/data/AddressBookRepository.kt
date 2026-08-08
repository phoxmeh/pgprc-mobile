package net.packetradio.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.CallsignEntity
import net.packetradio.mobile.data.entity.HeardBeaconEntity
import net.packetradio.mobile.data.entity.SsidEntity
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.model.CallsignEntry
import net.packetradio.mobile.model.HeardBeaconPacket
import net.packetradio.mobile.model.QrzResult
import net.packetradio.mobile.model.SsidEntry

/**
 * Domain-level wrapper over the `callsigns`/`ssids`/`heard_beacons` tables.
 *
 * The `callsigns` table holds user-editable callsign-level metadata (name, location, notes).
 * The `ssids` table holds one row per known SSID under each callsign: alias, tag, saved via paths,
 * and auto-populated heard statistics (lastHeard, heardCount, heardDirectly, currentId).
 */
class AddressBookRepository(private val db: PacketRadioDatabase) {

    /** All known callsigns with their SSIDs, sorted by base callsign. */
    fun observeAll(): Flow<List<CallsignEntry>> = combine(
        db.callsignDao().observeAll(),
        db.ssidDao().observeAll(),
    ) { callsigns, allSsids ->
        val ssidsByBase = allSsids.groupBy { it.baseCallsign }
        callsigns.map { it.toDomain(ssidsByBase[it.baseCallsign] ?: emptyList()) }
    }

    /** All known SSIDs as a flat list — used by the dial dialog autocomplete. */
    fun observeAllSsids(): Flow<List<SsidEntry>> =
        db.ssidDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeBeacons(callsign: String): Flow<List<HeardBeaconPacket>> =
        db.heardBeaconDao().observeForCallsign(callsign.uppercase()).map { list -> list.map { it.toDomain() } }

    fun observeBeaconsForBase(baseCallsign: String): Flow<List<HeardBeaconPacket>> =
        db.heardBeaconDao().observeForBase(baseCallsign.uppercase()).map { list -> list.map { it.toDomain() } }

    /** One-shot read of all callsigns+SSIDs for export. */
    suspend fun exportAll(): List<CallsignEntry> = observeAll().first()

    /**
     * Upserts all [entries] from an import — updates user-editable fields (name, location, notes,
     * alias, tag, via paths) and preserves auto-populated heard stats for rows that already exist.
     * Rows not mentioned in [entries] are left untouched.
     */
    suspend fun importFromEntries(entries: List<CallsignEntry>) {
        for (entry in entries) {
            val base = entry.baseCallsign.uppercase()
            ensureCallsign(base)
            updateCallsign(base, entry.name, entry.location, entry.notes)
            val hasQrzData = listOf(entry.firstName, entry.lastName, entry.address, entry.email).any { !it.isNullOrBlank() }
            if (hasQrzData) {
                updateQrzData(base, QrzResult(entry.firstName, entry.lastName, entry.address, entry.email, entry.lat, entry.lon))
            }
            for (ssid in entry.ssids) {
                addSsid(base, ssid.ssidNumber, ssid.userAlias, ssid.tag, ssid.viaPaths)
            }
        }
    }

    /** A station we directly received an RF frame from. */
    suspend fun recordHeard(fullCallsign: String, viaPath: String = "") {
        val (base, ssidNum) = fullCallsign.uppercase().splitCallsign()
        ensureCallsign(base)
        touchSsid(base, ssidNum, direct = true, viaPath = viaPath)
    }

    /**
     * Records the sender of a NET/ROM NODES broadcast plus its neighbors.
     * Each neighbor is Triple(fullCallsign, alias, via): via is non-null only for
     * directly-adjacent nodes (bestNeighbor == neighbor in the NODES record).
     */
    suspend fun recordNodeBroadcast(
        senderCallsign: String,
        senderAlias: String,
        neighbors: List<Triple<String, String, String?>>,
    ) {
        val (senderBase, senderSsid) = senderCallsign.uppercase().splitCallsign()
        ensureCallsign(senderBase)
        touchSsid(senderBase, senderSsid, direct = true, autoAlias = senderAlias)
        for ((callsign, alias, via) in neighbors) {
            val (base, ssidNum) = callsign.uppercase().splitCallsign()
            ensureCallsign(base)
            touchSsid(base, ssidNum, direct = false, autoAlias = alias, viaPath = via.orEmpty())
        }
    }

    /** Stores the node's current ID/BEACON text, skipping exact repeats. */
    suspend fun recordBeaconPacket(fullCallsign: String, text: String, timestamp: String = nowTimestamp()) {
        val call = fullCallsign.uppercase()
        if (db.heardBeaconDao().mostRecent(call)?.text == text) return
        db.heardBeaconDao().insert(HeardBeaconEntity(callsign = call, text = text, timestamp = timestamp))
        db.heardBeaconDao().pruneOldest(call, keep = 1)
        val (base, ssidNum) = call.splitCallsign()
        val existing = db.ssidDao().getByBaseAndSsid(base, ssidNum) ?: return
        db.ssidDao().update(existing.copy(currentId = text))
    }

    /** Updates callsign-level user-editable fields (name, location, notes). */
    suspend fun updateCallsign(baseCallsign: String, name: String?, location: String?, notes: String?) {
        val base = baseCallsign.uppercase()
        val existing = db.callsignDao().getByBase(base) ?: CallsignEntity(base, null, null, null)
        db.callsignDao().upsert(
            existing.copy(
                name = name?.trim()?.ifBlank { null },
                location = location?.trim()?.ifBlank { null },
                notes = notes?.trim()?.ifBlank { null },
            ),
        )
    }

    /** Writes QRZ-sourced fields for a callsign; safe to call if the row doesn't exist yet. */
    suspend fun updateQrzData(baseCallsign: String, result: QrzResult) {
        val base = baseCallsign.uppercase()
        ensureCallsign(base)
        db.callsignDao().updateQrzData(
            baseCallsign = base,
            firstName = result.firstName,
            lastName = result.lastName,
            address = result.address,
            email = result.email,
            lat = result.lat,
            lon = result.lon,
        )
    }

    /** Updates SSID-level user-editable fields (alias, tag, via paths). */
    suspend fun updateSsid(baseCallsign: String, ssidNumber: Int, userAlias: String?, tag: String?, viaPaths: List<String>) {
        val base = baseCallsign.uppercase()
        val existing = db.ssidDao().getByBaseAndSsid(base, ssidNumber) ?: return
        db.ssidDao().update(
            existing.copy(
                userAlias = userAlias?.trim()?.ifBlank { null },
                tag = tag?.trim()?.ifBlank { null },
                viaPathsRaw = viaPaths.filter { it.isNotBlank() }.joinToString("|"),
            ),
        )
    }

    /** Adds a manually-entered SSID entry under an existing callsign. */
    suspend fun addSsid(baseCallsign: String, ssidNumber: Int, userAlias: String?, tag: String?, viaPaths: List<String>) {
        val base = baseCallsign.uppercase()
        ensureCallsign(base)
        val existing = db.ssidDao().getByBaseAndSsid(base, ssidNumber)
        if (existing != null) {
            db.ssidDao().update(
                existing.copy(
                    userAlias = userAlias?.trim()?.ifBlank { null },
                    tag = tag?.trim()?.ifBlank { null },
                    viaPathsRaw = viaPaths.filter { it.isNotBlank() }.joinToString("|"),
                ),
            )
        } else {
            db.ssidDao().insert(
                SsidEntity(
                    baseCallsign = base,
                    ssidNumber = ssidNumber,
                    userAlias = userAlias?.trim()?.ifBlank { null },
                    tag = tag?.trim()?.ifBlank { null },
                    viaPathsRaw = viaPaths.filter { it.isNotBlank() }.joinToString("|"),
                    autoAlias = null,
                    lastHeard = null,
                    heardCount = 0,
                    heardDirectly = false,
                    currentId = null,
                ),
            )
        }
    }

    /** Deletes one SSID entry. Leaves the parent callsign row in place. */
    suspend fun deleteSsid(baseCallsign: String, ssidNumber: Int) {
        val base = baseCallsign.uppercase()
        val entity = db.ssidDao().getByBaseAndSsid(base, ssidNumber) ?: return
        db.ssidDao().delete(entity)
    }

    /** Deletes a callsign and all its SSIDs and heard beacons. */
    suspend fun deleteCallsign(baseCallsign: String) {
        val base = baseCallsign.uppercase()
        val entity = db.callsignDao().getByBase(base) ?: return
        db.ssidDao().deleteAllForBase(base)
        // heard_beacons are keyed by full callsign — delete all that start with this base
        for (ssid in db.ssidDao().getAllForBase(base)) {
            db.heardBeaconDao().deleteForCallsign(ssid.baseCallsign + if (ssid.ssidNumber == 0) "" else "-${ssid.ssidNumber}")
        }
        db.heardBeaconDao().deleteForCallsign(base)
        db.callsignDao().delete(entity)
    }

    private suspend fun ensureCallsign(base: String) {
        if (db.callsignDao().getByBase(base) == null) {
            db.callsignDao().upsert(CallsignEntity(baseCallsign = base, name = null, location = null, notes = null))
        }
    }

    private suspend fun touchSsid(
        base: String,
        ssidNum: Int,
        direct: Boolean,
        autoAlias: String? = null,
        viaPath: String = "",
    ) {
        val existing = db.ssidDao().getByBaseAndSsid(base, ssidNum)
        if (existing != null) {
            db.ssidDao().update(
                existing.copy(
                    heardCount = existing.heardCount + 1,
                    lastHeard = nowTimestamp(),
                    heardDirectly = existing.heardDirectly || direct,
                    autoAlias = autoAlias?.takeIf { it.isNotBlank() } ?: existing.autoAlias,
                    // Prepend newly learned via path if not already in the list and it's non-blank
                    viaPathsRaw = mergeVia(existing.viaPathsRaw, viaPath),
                ),
            )
        } else {
            db.ssidDao().insert(
                SsidEntity(
                    baseCallsign = base,
                    ssidNumber = ssidNum,
                    userAlias = null,
                    tag = null,
                    viaPathsRaw = viaPath.ifBlank { "" },
                    autoAlias = autoAlias?.takeIf { it.isNotBlank() },
                    lastHeard = nowTimestamp(),
                    heardCount = 1,
                    heardDirectly = direct,
                    currentId = null,
                ),
            )
        }
    }

    /** Adds [newVia] to the pipe-separated [existing] list if non-blank and not already present. */
    private fun mergeVia(existing: String, newVia: String): String {
        if (newVia.isBlank()) return existing
        val paths = if (existing.isBlank()) emptyList() else existing.split("|")
        return if (newVia in paths) existing else (paths + newVia).joinToString("|")
    }
}

/** Parses `KD3BFP-9` → `("KD3BFP", 9)`, `KD3BFP` → `("KD3BFP", 0)`. */
fun String.splitCallsign(): Pair<String, Int> {
    val dashIdx = lastIndexOf('-')
    if (dashIdx < 0) return this to 0
    val ssidPart = substring(dashIdx + 1).toIntOrNull() ?: return this to 0
    if (ssidPart !in 0..15) return this to 0
    return substring(0, dashIdx) to ssidPart
}
