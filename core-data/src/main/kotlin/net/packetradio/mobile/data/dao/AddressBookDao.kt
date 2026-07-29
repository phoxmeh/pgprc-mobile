package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.AddressBookEntity

@Dao
interface AddressBookDao {
    @Query("SELECT * FROM address_book ORDER BY lastHeard DESC, callsign ASC")
    fun observeAll(): Flow<List<AddressBookEntity>>

    @Query("SELECT * FROM address_book WHERE callsign = :callsign")
    suspend fun getByCallsign(callsign: String): AddressBookEntity?

    @Upsert
    suspend fun upsert(entry: AddressBookEntity)

    @Delete
    suspend fun delete(entry: AddressBookEntity)
}
