package net.packetradio.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.MailboxMessage

@Entity(tableName = "mailbox_messages")
data class MailboxMessageEntity(
    @PrimaryKey val id: Long,
    // "to"/"from" are ordinary Kotlin identifiers but explicit column names
    // sidestep any doubt about SQL keyword ambiguity.
    @ColumnInfo(name = "to_call") val to: String,
    @ColumnInfo(name = "from_call") val from: String,
    val subject: String,
    val body: String,
    val timestamp: String,
    val read: Boolean,
)

fun MailboxMessage.toEntity(): MailboxMessageEntity = MailboxMessageEntity(
    id = id, to = to, from = from, subject = subject, body = body, timestamp = timestamp, read = read,
)

fun MailboxMessageEntity.toDomain(): MailboxMessage = MailboxMessage(
    id = id, to = to, from = from, subject = subject, body = body, timestamp = timestamp, read = read,
)
