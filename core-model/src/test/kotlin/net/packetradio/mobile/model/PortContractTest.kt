package net.packetradio.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PortContractTest {

    @Test
    fun `Data events with equal content but different array instances are equal`() {
        val a = PortEvent.Data(id = 1L, bytes = byteArrayOf(1, 2, 3))
        val b = PortEvent.Data(id = 1L, bytes = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Data events with different content are not equal`() {
        val a = PortEvent.Data(id = 1L, bytes = byteArrayOf(1, 2, 3))
        val b = PortEvent.Data(id = 1L, bytes = byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `SendUnproto commands compare by content`() {
        val a = PortCommand.SendUnproto(dest = "CQ", via = listOf("WIDE1-1"), bytes = byteArrayOf(9, 9))
        val b = PortCommand.SendUnproto(dest = "CQ", via = listOf("WIDE1-1"), bytes = byteArrayOf(9, 9))
        assertEquals(a, b)
    }
}
