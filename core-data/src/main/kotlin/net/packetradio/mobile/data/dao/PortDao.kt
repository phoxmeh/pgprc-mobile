package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.PortEntryEntity

@Dao
interface PortDao {
    @Query("SELECT * FROM ports ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<PortEntryEntity>>

    @Query("SELECT * FROM ports ORDER BY sortOrder ASC")
    suspend fun getAll(): List<PortEntryEntity>

    @Query("SELECT * FROM ports WHERE id = :id")
    suspend fun getById(id: String): PortEntryEntity?

    @Upsert
    suspend fun upsert(port: PortEntryEntity)

    @Delete
    suspend fun delete(port: PortEntryEntity)

    @Query("DELETE FROM ports WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM ports")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE ports SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)
}
