package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.HighlightRule

/** The domain [HighlightRule] has no id; Room needs one, dropped on the way back. */
@Entity(tableName = "highlight_rules")
data class HighlightRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val pattern: String,
    val color: String,
    val notify: Boolean,
    val enabled: Boolean,
)

fun HighlightRule.toEntity(): HighlightRuleEntity = HighlightRuleEntity(
    label = label, pattern = pattern, color = color, notify = notify, enabled = enabled,
)

fun HighlightRuleEntity.toDomain(): HighlightRule = HighlightRule(
    label = label, pattern = pattern, color = color, notify = notify, enabled = enabled,
)
