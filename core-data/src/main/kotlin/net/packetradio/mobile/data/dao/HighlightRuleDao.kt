package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.HighlightRuleEntity

@Dao
interface HighlightRuleDao {
    @Query("SELECT * FROM highlight_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<HighlightRuleEntity>>

    @Query("SELECT * FROM highlight_rules ORDER BY id ASC")
    suspend fun getAll(): List<HighlightRuleEntity>

    @Query("SELECT COUNT(*) FROM highlight_rules")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(rule: HighlightRuleEntity)

    @Upsert
    suspend fun upsertAll(rules: List<HighlightRuleEntity>)

    @Delete
    suspend fun delete(rule: HighlightRuleEntity)
}
