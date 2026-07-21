package com.jeff.gbxsync

import com.jeff.gbxsync.cart.AgbHeader
import com.jeff.gbxsync.cart.DmgHeader
import com.jeff.gbxsync.cart.DmgSaveType
import com.jeff.gbxsync.cart.Mbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadersTest {

    private fun dmgRom(
        title: String = "POKEMON RED",
        cartType: Int = 0x03, // MBC1+RAM+BATTERY
        ramSize: Int = 0x03,  // 32 KB
    ): ByteArray {
        val rom = ByteArray(0x180)
        // Logo prefix
        byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66,
            0xCC.toByte(), 0x0D, 0x00, 0x0B,
        ).copyInto(rom, 0x104)
        title.toByteArray(Charsets.US_ASCII).copyInto(rom, 0x134)
        rom[0x147] = cartType.toByte()
        rom[0x148] = 0x05
        rom[0x149] = ramSize.toByte()
        var chk = 0
        for (i in 0x134..0x14C) chk = chk - (rom[i].toInt() and 0xFF) - 1
        rom[0x14D] = (chk and 0xFF).toByte()
        return rom
    }

    private fun agbRom(title: String = "POKEMON EMER", code: String = "BPEE"): ByteArray {
        val rom = ByteArray(0x180)
        title.toByteArray(Charsets.US_ASCII).copyInto(rom, 0xA0)
        code.toByteArray(Charsets.US_ASCII).copyInto(rom, 0xAC)
        "01".toByteArray(Charsets.US_ASCII).copyInto(rom, 0xB0)
        rom[0xB2] = 0x96.toByte()
        var sum = 0
        for (i in 0xA0..0xBC) sum += rom[i].toInt() and 0xFF
        rom[0xBD] = ((-(sum + 0x19)) and 0xFF).toByte()
        return rom
    }

    @Test
    fun parsesDmgHeader() {
        val h = DmgHeader.parse(dmgRom())
        assertTrue(h.isValid)
        assertEquals("POKEMON RED", h.title)
        assertEquals(0x03, h.cartType)
        val save = DmgSaveType.fromHeader(h)!!
        assertEquals(Mbc.MBC1, save.mbc)
        assertEquals(32768, save.sizeBytes)
        assertEquals(4, save.bankCount)
    }

    @Test
    fun mbc2SaveSizeOverride() {
        val h = DmgHeader.parse(dmgRom(cartType = 0x06, ramSize = 0x00))
        val save = DmgSaveType.fromHeader(h)!!
        assertEquals(Mbc.MBC2, save.mbc)
        assertEquals(512, save.sizeBytes)
        assertEquals(1, save.bankCount)
    }

    @Test
    fun unsupportedMapperReturnsNull() {
        val h = DmgHeader.parse(dmgRom(cartType = 0x22)) // MBC7
        assertEquals(null, DmgSaveType.fromHeader(h))
    }

    @Test
    fun invalidDmgHeaderRejected() {
        val rom = dmgRom()
        rom[0x104] = 0x00 // break logo
        assertFalse(DmgHeader.parse(rom).isValid)
    }

    @Test
    fun parsesAgbHeader() {
        val h = AgbHeader.parse(agbRom())
        assertTrue(h.isValid)
        assertEquals("POKEMON EMER", h.title)
        assertEquals("BPEE", h.gameCode)
    }

    @Test
    fun invalidAgbComplementRejected() {
        val rom = agbRom()
        rom[0xBD] = (rom[0xBD] + 1).toByte()
        assertFalse(AgbHeader.parse(rom).isValid)
    }
}
