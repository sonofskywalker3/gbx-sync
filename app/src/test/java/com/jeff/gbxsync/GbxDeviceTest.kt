package com.jeff.gbxsync

import com.jeff.gbxsync.protocol.CartMode
import com.jeff.gbxsync.protocol.GbxDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbxDeviceTest {

    private fun connectedDevice(transport: FakeTransport): GbxDevice {
        val device = GbxDevice(transport)
        // pcb=6 (v1.4a), ofw=31, QUERY_FW_INFO: len=8, 'L', fwVer=12, pcb=6, ts
        transport.queue(6)
        transport.queue(31)
        transport.queue(8)
        transport.queue('L'.code)
        transport.queue(0x00, 0x0C) // fw 12 BE
        transport.queue(6)
        transport.queue(0x60, 0x00, 0x00, 0x00) // timestamp
        // name block: len=10 "GBxCart RW", features=0x01 (power ctrl), bootloader=1
        transport.queue(10)
        transport.queue("GBxCart RW".toByteArray())
        transport.queue(0x01)
        transport.queue(0x01)
        val fw = device.connect()
        assertEquals("L", fw.cfwId)
        assertEquals(12, fw.fwVer)
        assertEquals(6, fw.pcbVer)
        assertEquals("GBxCart RW", fw.pcbName)
        assertTrue(fw.cartPowerCtrl)
        transport.written.clear()
        return device
    }

    @Test
    fun connectParsesFirmwareInfo() {
        connectedDevice(FakeTransport())
    }

    @Test
    fun setVariablePacketLayout() {
        val t = FakeTransport()
        val d = connectedDevice(t)
        t.queue(0x01) // ack (fw >= 12 acks SET_VARIABLE)
        d.setVariable(com.jeff.gbxsync.protocol.FwVar.TRANSFER_SIZE, 0x200)
        // [0xA6][width=2][key u32][value u32]
        assertArrayEquals(
            byteArrayOf(
                0xA6.toByte(), 0x02,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x02, 0x00,
            ),
            t.allWritten,
        )
    }

    @Test
    fun dmgCartWritePacketLayout() {
        val t = FakeTransport()
        val d = connectedDevice(t)
        // setMode(DMG): mode ack, voltage ack, 3 variable acks, power query=1, MBC reset ack
        t.queue(0x01, 0x01, 0x01, 0x01, 0x01)
        t.queue(0x01) // QUERY_CART_PWR -> already on
        t.queue(0x01) // DMG_MBC_RESET ack
        d.setMode(CartMode.DMG)
        t.written.clear()

        t.queue(0x01) // ack
        d.cartWrite(0x4000, 0x0A)
        assertArrayEquals(
            byteArrayOf(0xB2.toByte(), 0x00, 0x00, 0x40, 0x00, 0x0A),
            t.allWritten,
        )
    }

    @Test
    fun agbCartWriteHalvesAddress() {
        val t = FakeTransport()
        val d = connectedDevice(t)
        // setMode(AGB): mode ack, voltage ack, 3 variable acks (read method, irq, cart mode) + address
        t.queue(0x01, 0x01, 0x01, 0x01, 0x01, 0x01)
        t.queue(0x01) // power query -> on
        t.queue(0x01) // AGB bootup ack
        d.setMode(CartMode.AGB)
        t.written.clear()

        t.queue(0x01)
        d.cartWrite(0x5555 * 2, 0xAA)
        assertArrayEquals(
            byteArrayOf(0xC2.toByte(), 0x00, 0x00, 0x55, 0x55, 0x00, 0xAA.toByte()),
            t.allWritten,
        )
    }

    @Test
    fun bulkReadChunksAndAssembles() {
        val t = FakeTransport()
        val d = connectedDevice(t)
        t.queue(0x01, 0x01, 0x01, 0x01, 0x01)
        t.queue(0x01)
        t.queue(0x01)
        d.setMode(CartMode.DMG)
        t.written.clear()

        // readRom(0, 0x180): TRANSFER_SIZE ack, ADDRESS ack, ACCESS_MODE ack, then data
        t.queue(0x01, 0x01, 0x01)
        val payload = ByteArray(0x180) { (it % 251).toByte() }
        t.queue(payload)
        val result = d.readRom(0, 0x180)
        assertArrayEquals(payload, result)
        // Last write should be the single-byte DMG_CART_READ command
        assertArrayEquals(byteArrayOf(0xB1.toByte()), t.written.last())
    }

    @Test
    fun writeRamSendsChunksWithAck() {
        val t = FakeTransport()
        val d = connectedDevice(t)
        t.queue(0x01, 0x01, 0x01, 0x01, 0x01)
        t.queue(0x01)
        t.queue(0x01)
        d.setMode(CartMode.DMG)
        t.written.clear()

        val data = ByteArray(0x200) { it.toByte() }
        // vars: TRANSFER_SIZE, ADDRESS, ACCESS_MODE, CS_PULSE acks; 2 chunk acks; trailing ADDRESS + CS_PULSE acks
        t.queue(0x01, 0x01, 0x01, 0x01)
        t.queue(0x01, 0x01)
        t.queue(0x01, 0x01)
        d.writeRam(0, data, maxChunk = 0x100)
        // Expect two 0xB3 command bytes among writes
        val commandWrites = t.written.count { it.size == 1 && it[0] == 0xB3.toByte() }
        assertEquals(2, commandWrites)
    }
}
