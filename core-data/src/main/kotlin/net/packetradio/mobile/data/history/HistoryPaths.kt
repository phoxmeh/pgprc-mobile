package net.packetradio.mobile.data.history

import java.io.File

/**
 * Per-node plain-text scrollback/capture file layout. Ported verbatim from
 * `pr-core::history_paths` — same sanitization and naming rules, just rooted
 * under the app's private `filesDir` instead of `~/.config/packet-radio/`.
 */
object HistoryPaths {

    /**
     * Trims [s], replaces any character that isn't alphanumeric or one of
     * `-`, `_`, `.` with `_`; an empty result becomes `"_"`.
     */
    fun sanitizeComponent(s: String): String {
        val trimmed = s.trim()
        val sanitized = trimmed.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("")
        return sanitized.ifEmpty { "_" }
    }

    /** `<baseDir>/history/<sanitize(portName)>/` */
    fun historyDir(baseDir: File, portName: String): File =
        File(File(baseDir, "history"), sanitizeComponent(portName))

    /**
     * The auto-managed, ever-appended scrollback file for one (port, node,
     * mode) conversation: `<remote>.txt`, or `<remote>_unproto.txt` when
     * [unproto] is true.
     */
    fun historyFilePath(baseDir: File, portName: String, remote: String, unproto: Boolean): File {
        val suffix = if (unproto) "_unproto.txt" else ".txt"
        return File(historyDir(baseDir, portName), sanitizeComponent(remote) + suffix)
    }

    /**
     * A one-off dated capture file (manual "Save..." export or the
     * live-capture toggle): `<node>_<date>_<time>.txt`, same directory as
     * [historyFilePath]. Caller supplies [date]/[time] already formatted
     * (e.g. `YYYY-MM-DD` / `HHMMSS`) so multiple captures per conversation
     * don't collide or overwrite.
     */
    fun captureFilePath(baseDir: File, portName: String, node: String, date: String, time: String): File =
        File(historyDir(baseDir, portName), "${sanitizeComponent(node)}_${date}_${time}.txt")
}
