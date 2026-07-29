package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.AddressBookEntry

@Entity(tableName = "address_book")
data class AddressBookEntity(
    @PrimaryKey val callsign: String,
    val name: String?,
    val alias: String?,
    val location: String?,
    val notes: String?,
    val lastHeard: String?,
    val heardCount: Int,
    val via: String,
)

fun AddressBookEntry.toEntity(): AddressBookEntity = AddressBookEntity(
    callsign = callsign,
    name = name,
    alias = alias,
    location = location,
    notes = notes,
    lastHeard = lastHeard,
    heardCount = heardCount,
    via = via,
)

fun AddressBookEntity.toDomain(): AddressBookEntry = AddressBookEntry(
    callsign = callsign,
    name = name,
    alias = alias,
    location = location,
    notes = notes,
    lastHeard = lastHeard,
    heardCount = heardCount,
    via = via,
)
