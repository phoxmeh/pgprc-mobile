package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * A user-defined destination-address rule: highlights matching traffic and,
 * if [notify] is set, also raises a notification. Ported from
 * `pr-core::HighlightRule` — comma/pipe-separated literal destination
 * tokens, matched whole-word and case-insensitively.
 */
@Serializable
data class HighlightRule(
    val label: String,
    val pattern: String,
    val color: String,
    val notify: Boolean = false,
    val enabled: Boolean = true,
)

fun defaultHighlightRules(): List<HighlightRule> = listOf(
    HighlightRule(label = "CQ", pattern = "CQ", color = "#FFD700", notify = false, enabled = true),
    HighlightRule(label = "BEACON/IDENT", pattern = "BEACON,IDENT", color = "#FF8C00", notify = false, enabled = true),
)

/**
 * Highlighting toggle + built-in category colors. [HighlightRule]s
 * themselves live in their own Room table (mirrors the desktop's
 * `rules.toml` split), not here. Ported from `pr-core::HighlightPrefs`.
 */
@Serializable
data class HighlightPrefs(
    val enabled: Boolean = true,
    val callsignColor: String = "#4FC1FF",
    val knownCallsignColor: String = "#B5CEA8",
    val myCallColor: String = "#FF5555",
    val ax25CommandColor: String = "#C586C0",
)
