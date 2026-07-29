package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.MailboxMessageEntity

@Dao
interface MailboxMessageDao {
    @Query("SELECT * FROM mailbox_messages ORDER BY id DESC")
    fun observeAll(): Flow<List<MailboxMessageEntity>>

    @Query("SELECT * FROM mailbox_messages WHERE to_call = :to AND read = 0 ORDER BY id ASC")
    fun observeUnreadFor(to: String): Flow<List<MailboxMessageEntity>>

    @Upsert
    suspend fun upsert(message: MailboxMessageEntity)

    @Delete
    suspend fun delete(message: MailboxMessageEntity)
}
