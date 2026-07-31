package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.WatchedDestination

@Entity(tableName = "watched_destinations")
data class WatchedDestinationEntity(
    @PrimaryKey val id: String,
    val destination: String,
    val enabled: Boolean,
)

fun WatchedDestination.toEntity(): WatchedDestinationEntity =
    WatchedDestinationEntity(id = id, destination = destination, enabled = enabled)

fun WatchedDestinationEntity.toDomain(): WatchedDestination =
    WatchedDestination(id = id, destination = destination, enabled = enabled)
