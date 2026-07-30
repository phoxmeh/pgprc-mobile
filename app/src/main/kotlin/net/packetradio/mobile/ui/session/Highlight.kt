package net.packetradio.mobile.ui.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.HighlightRule

private val PORT_TAG_REGEX = Regex("^\\[[^\\]]+]\\s*")
private val BRACKET_TAG_REGEX = Regex("\\[([^\\]]{1,40})]")
private val CALLSIGN_REGEX = Regex("\\b[A-Za-z0-9]{3,7}(-\\d{1,2})?\\b")

/** Leading token (before a space) of every bracketed tag this app's own transports emit. */
private val AX25_MNEMONICS = setOf(
    "G", "R", "H", "g", "U", "S", "I", "T", "C", "D", "d",
    "UI", "RR", "RNR", "REJ", "SABM", "DISC", "DM", "UA", "FRMR", "unproto",
)

private fun parseHexColor(hex: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    fallback
}

private fun baseCall(call: String): String = call.trim().uppercase().substringBefore('-')

/**
 * A real AX.25/ham callsign always mixes letters and digits (ITU prefix +
 * digit + suffix); a plain English word never contains a digit, and a raw
 * number never contains a letter. Requiring both is what keeps this from
 * lighting up ordinary log prose like "ERROR"/"port"/"8000"/"failed" as if
 * they were callsigns — found by live-testing against a real connection
 * error line, not just eyeballing the regex.
 */
private fun looksLikeCallsign(token: String): Boolean {
    var hasLetter = false
    var hasDigit = false
    for (c in token) {
        if (c.isLetter()) hasLetter = true
        if (c.isDigit()) hasDigit = true
    }
    return hasLetter && hasDigit
}

/**
 * Colors a Monitor/session line the same three-tier way the desktop app does
 * — the user's own callsign, other callsign-shaped tokens, and bracketed
 * AX.25/AGWPE frame-tag mnemonics — plus any enabled [HighlightRule] keyword
 * match, all layered by priority so a keyword rule (e.g. "CQ") isn't stolen
 * by the generic callsign-shape pass just because it happens to look like
 * one. A leading `"[port label] "` prefix (this app's own tagging, prepended
 * in [SessionViewModel], not part of the actual packet) is rendered muted
 * rather than colored like real frame content. A line starting with "ERROR:"
 * (right after that prefix) is rendered entirely in [errorColor] instead —
 * it's diagnostic text, not packet content, so frame/callsign coloring
 * inside it would be misleading rather than helpful.
 */
fun highlightMonitorLine(
    line: String,
    myCall: String,
    prefs: HighlightPrefs,
    rules: List<HighlightRule>,
    mutedColor: Color,
    errorColor: Color,
): AnnotatedString {
    val portTagMatch = PORT_TAG_REGEX.find(line)
    val contentStart = portTagMatch?.range?.last?.plus(1) ?: 0

    if (line.startsWith("ERROR:", contentStart)) {
        return buildAnnotatedString {
            append(line)
            if (contentStart > 0) addStyle(SpanStyle(color = mutedColor), 0, contentStart)
            addStyle(SpanStyle(color = errorColor), contentStart, line.length)
        }
    }

    if (!prefs.enabled) return AnnotatedString(line)

    val myCallColor = parseHexColor(prefs.myCallColor, Color.Red)
    val callsignColor = parseHexColor(prefs.callsignColor, Color.Cyan)
    val commandColor = parseHexColor(prefs.ax25CommandColor, Color.Magenta)

    // Highest-priority claim wins; a slot already claimed is left alone by later passes.
    val claimed = arrayOfNulls<Color>(line.length)
    fun claim(range: IntRange, color: Color) {
        for (i in range) if (i in line.indices && claimed[i] == null) claimed[i] = color
    }

    val myBase = baseCall(myCall)
    if (myBase.isNotEmpty()) {
        for (match in CALLSIGN_REGEX.findAll(line)) {
            if (match.range.first < contentStart || !looksLikeCallsign(match.value)) continue
            if (baseCall(match.value) == myBase) claim(match.range, myCallColor)
        }
    }

    for (match in BRACKET_TAG_REGEX.findAll(line)) {
        if (match.range.first < contentStart) continue
        val leadWord = match.groupValues[1].substringBefore(' ')
        if (leadWord in AX25_MNEMONICS) claim(match.range, commandColor)
    }

    for (rule in rules) {
        if (!rule.enabled) continue
        val ruleColor = parseHexColor(rule.color, callsignColor)
        for (pattern in rule.pattern.split(',', '|')) {
            val token = pattern.trim()
            if (token.isEmpty()) continue
            var idx = line.indexOf(token, contentStart, ignoreCase = true)
            while (idx >= 0) {
                claim(idx until idx + token.length, ruleColor)
                idx = line.indexOf(token, idx + token.length, ignoreCase = true)
            }
        }
    }

    for (match in CALLSIGN_REGEX.findAll(line)) {
        if (match.range.first < contentStart || !looksLikeCallsign(match.value)) continue
        claim(match.range, callsignColor)
    }

    return buildAnnotatedString {
        append(line)
        if (contentStart > 0) addStyle(SpanStyle(color = mutedColor), 0, contentStart)
        var i = contentStart
        while (i < line.length) {
            val color = claimed[i]
            if (color == null) {
                i++
                continue
            }
            var j = i
            while (j < line.length && claimed[j] == color) j++
            addStyle(SpanStyle(color = color), i, j)
            i = j
        }
    }
}
