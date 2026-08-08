package net.packetradio.mobile.data

import net.packetradio.mobile.model.CallsignEntry
import net.packetradio.mobile.model.SsidEntry

/**
 * Minimal TOML serializer/deserializer for the address book.
 *
 * Only the subset actually needed is supported:
 *   [[callsign]] and [[ssid]] array-table headers
 *   key = "string", key = 123, key = ["a", "b", "c"]
 *   Line comments (#), blank lines
 */

fun exportTomlString(entries: List<CallsignEntry>): String {
    val sb = StringBuilder()
    sb.append("# PGPRC Mobile address book export\n\n")
    for (entry in entries.sortedBy { it.baseCallsign }) {
        sb.append("[[callsign]]\n")
        sb.append("base = ${tomlStr(entry.baseCallsign)}\n")
        sb.append("name = ${tomlStr(entry.name ?: "")}\n")
        sb.append("location = ${tomlStr(entry.location ?: "")}\n")
        sb.append("notes = ${tomlStr(entry.notes ?: "")}\n")
        sb.append("\n")
        for (ssid in entry.ssids.sortedBy { it.ssidNumber }) {
            sb.append("[[ssid]]\n")
            sb.append("base = ${tomlStr(ssid.baseCallsign)}\n")
            sb.append("ssid = ${ssid.ssidNumber}\n")
            sb.append("alias = ${tomlStr(ssid.userAlias ?: "")}\n")
            sb.append("tag = ${tomlStr(ssid.tag ?: "")}\n")
            sb.append("via = [${ssid.viaPaths.joinToString(", ") { tomlStr(it) }}]\n")
            sb.append("\n")
        }
    }
    return sb.toString()
}

fun parseTomlEntries(toml: String): List<CallsignEntry> {
    // Ordered map so we keep declaration order for callsigns
    val callsigns = linkedMapOf<String, MutableMap<String, String>>()
    val ssidBlocks = mutableListOf<MutableMap<String, Any>>()

    var currentTable: String? = null
    var currentBlock = mutableMapOf<String, Any>()

    fun flush() {
        when (currentTable) {
            "callsign" -> {
                val base = (currentBlock["base"] as? String)?.uppercase() ?: return
                callsigns[base] = mutableMapOf<String, String>().also { m ->
                    currentBlock.forEach { (k, v) -> if (v is String) m[k] = v }
                }
            }
            "ssid" -> {
                if (currentBlock.containsKey("base")) ssidBlocks.add(HashMap(currentBlock))
            }
        }
    }

    for (line in toml.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
            flush()
            currentTable = trimmed.drop(2).dropLast(2).trim()
            currentBlock = mutableMapOf()
            continue
        }
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx < 0) continue
        val key = trimmed.substring(0, eqIdx).trim()
        val valStr = trimmed.substring(eqIdx + 1).trim()
        when {
            valStr.startsWith("\"") -> currentBlock[key] = parseTomlString(valStr)
            valStr.startsWith("[") -> currentBlock[key] = parseTomlStringArray(valStr)
            else -> valStr.toIntOrNull()?.let { currentBlock[key] = it }
        }
    }
    flush()

    return callsigns.map { (base, fields) ->
        val mySsids = ssidBlocks
            .filter { (it["base"] as? String)?.uppercase() == base }
            .map { block ->
                @Suppress("UNCHECKED_CAST")
                SsidEntry(
                    baseCallsign = base,
                    ssidNumber = (block["ssid"] as? Int) ?: 0,
                    userAlias = (block["alias"] as? String)?.ifBlank { null },
                    tag = (block["tag"] as? String)?.ifBlank { null },
                    viaPaths = (block["via"] as? List<String>) ?: emptyList(),
                )
            }
        CallsignEntry(
            baseCallsign = base,
            name = fields["name"]?.ifBlank { null },
            location = fields["location"]?.ifBlank { null },
            notes = fields["notes"]?.ifBlank { null },
            ssids = mySsids,
        )
    }
}

private fun tomlStr(s: String): String {
    val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    return "\"$esc\""
}

private fun parseTomlString(raw: String): String {
    val inner = raw.trimStart().removePrefix("\"").removeSuffix("\"")
    return inner
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\\\", "\\")
}

private fun parseTomlStringArray(raw: String): List<String> {
    val inner = raw.trim().removePrefix("[").removeSuffix("]").trim()
    if (inner.isEmpty()) return emptyList()
    return inner.split(",").map { parseTomlString(it.trim()) }.filter { it.isNotBlank() }
}
