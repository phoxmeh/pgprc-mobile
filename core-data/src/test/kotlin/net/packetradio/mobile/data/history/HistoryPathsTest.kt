package net.packetradio.mobile.data.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPathsTest {

    @Test
    fun `sanitizeComponent replaces disallowed characters`() {
        assertEquals("KD3BFP-9", HistoryPaths.sanitizeComponent("KD3BFP-9"))
        assertEquals("Direwolf_TCP", HistoryPaths.sanitizeComponent("Direwolf TCP"))
        assertEquals("a_b_c", HistoryPaths.sanitizeComponent("a/b\\c"))
    }

    @Test
    fun `sanitizeComponent trims and falls back to underscore when empty`() {
        assertEquals("x", HistoryPaths.sanitizeComponent("  x  "))
        assertEquals("_", HistoryPaths.sanitizeComponent("   "))
        assertEquals("_", HistoryPaths.sanitizeComponent(""))
    }

    @Test
    fun `historyDir nests under history slash sanitized port name`() {
        val base = File("/data/app")
        val dir = HistoryPaths.historyDir(base, "Direwolf TCP")
        assertEquals(File("/data/app/history/Direwolf_TCP"), dir)
    }

    @Test
    fun `historyFilePath distinguishes connected-mode and unproto for the same node`() {
        val base = File("/data/app")
        val connected = HistoryPaths.historyFilePath(base, "Direwolf", "N0CALL-1", unproto = false)
        val unproto = HistoryPaths.historyFilePath(base, "Direwolf", "N0CALL-1", unproto = true)
        assertEquals(File("/data/app/history/Direwolf/N0CALL-1.txt"), connected)
        assertEquals(File("/data/app/history/Direwolf/N0CALL-1_unproto.txt"), unproto)
    }

    @Test
    fun `captureFilePath includes date and time so repeated captures do not collide`() {
        val base = File("/data/app")
        val capture = HistoryPaths.captureFilePath(base, "Direwolf", "N0CALL-1", "2026-07-28", "120500")
        assertEquals(File("/data/app/history/Direwolf/N0CALL-1_2026-07-28_120500.txt"), capture)
    }
}
