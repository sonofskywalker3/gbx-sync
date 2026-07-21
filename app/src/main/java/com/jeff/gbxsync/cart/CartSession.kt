package com.jeff.gbxsync.cart

import com.jeff.gbxsync.protocol.CartMode
import com.jeff.gbxsync.protocol.GbxDevice
import com.jeff.gbxsync.protocol.GbxException

/** Everything known about the currently inserted cartridge. */
sealed class CartInfo {
    abstract val title: String
    abstract val headerBytes: ByteArray

    data class Dmg(
        val header: DmgHeader,
        override val headerBytes: ByteArray,
        val saveType: DmgSaveType?,
    ) : CartInfo() {
        override val title get() = header.title
    }

    data class Agb(
        val header: AgbHeader,
        override val headerBytes: ByteArray,
        val detection: AgbCart.Detection,
    ) : CartInfo() {
        override val title get() = header.title
    }

    val saveSizeBytes: Int
        get() = when (this) {
            is Dmg -> saveType?.sizeBytes ?: 0
            is Agb -> detection.saveType.sizeBytes
        }

    val systemLabel: String get() = if (this is Dmg) "Game Boy" else "Game Boy Advance"
}

/**
 * Detects the inserted cartridge and reads/writes its save through the right
 * cart driver. Probes GBA first (3.3 V is safe for both cart types; 5 V is
 * not something we want on a GBA cart unless we know it's a DMG cart slot use).
 */
class CartSession(private val device: GbxDevice) {

    fun detect(): CartInfo? {
        // AGB probe at 3.3V
        device.setMode(CartMode.AGB)
        val agbHeaderBytes = device.readRom(0, AgbHeader.HEADER_LENGTH)
        val agbHeader = AgbHeader.parse(agbHeaderBytes)
        if (agbHeader.isValid) {
            val detection = AgbCart(device).detectSaveType()
            return CartInfo.Agb(agbHeader, agbHeaderBytes, detection)
        }

        // DMG probe at 5V
        device.setMode(CartMode.DMG)
        val dmgHeaderBytes = device.readRom(0, DmgHeader.HEADER_LENGTH)
        val dmgHeader = DmgHeader.parse(dmgHeaderBytes)
        if (dmgHeader.isValid) {
            return CartInfo.Dmg(dmgHeader, dmgHeaderBytes, DmgSaveType.fromHeader(dmgHeader))
        }
        device.cartPowerOff()
        return null
    }

    fun readSave(info: CartInfo, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray =
        when (info) {
            is CartInfo.Dmg -> {
                val st = info.saveType ?: throw GbxException("Unsupported or save-less cartridge")
                ensureMode(CartMode.DMG)
                DmgCart(device).readSave(st, progress)
            }
            is CartInfo.Agb -> {
                ensureMode(CartMode.AGB)
                AgbCart(device).readSave(info.detection.saveType, progress)
            }
        }

    fun writeSave(info: CartInfo, data: ByteArray, progress: (Int, Int) -> Unit = { _, _ -> }) {
        when (info) {
            is CartInfo.Dmg -> {
                val st = info.saveType ?: throw GbxException("Unsupported or save-less cartridge")
                ensureMode(CartMode.DMG)
                DmgCart(device).writeSave(st, data, progress)
            }
            is CartInfo.Agb -> {
                ensureMode(CartMode.AGB)
                AgbCart(device).writeSave(
                    info.detection.saveType, data, info.detection.flashChipId, progress
                )
            }
        }
    }

    /** Read-back verify after a cart write. */
    fun verifySave(info: CartInfo, expected: ByteArray): Boolean {
        val actual = readSave(info)
        if (info is CartInfo.Dmg && info.saveType?.mbc == Mbc.MBC2) {
            return actual.indices.all {
                (actual[it].toInt() and 0x0F) == (expected[it].toInt() and 0x0F)
            }
        }
        return actual.contentEquals(expected.copyOf(actual.size))
    }

    private fun ensureMode(mode: CartMode) {
        if (device.mode != mode) device.setMode(mode)
    }
}
