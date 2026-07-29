package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/** A stored personal-mailbox message. Ported from `pr-core::MailboxMessage`. */
@Serializable
data class MailboxMessage(
    val id: Long,
    val to: String,
    val from: String,
    val subject: String,
    val body: String,
    val timestamp: String,
    val read: Boolean = false,
)

/**
 * Just the on/off switch — stored messages live in their own Room table, not
 * here, mirroring the desktop's `mailbox.toml` split (`MailboxPrefs.messages`
 * was `#[serde(skip)]` there too).
 */
@Serializable
data class MailboxPrefs(
    val enabled: Boolean = false,
)

/** The state machine driving an auto-responded mailbox conversation. */
sealed interface MailboxState {
    data object AwaitingCommand : MailboxState
    data class AwaitingSendSubject(val to: String) : MailboxState
    data class AwaitingSendBody(val to: String, val subject: String, val body: List<String>) : MailboxState
}
