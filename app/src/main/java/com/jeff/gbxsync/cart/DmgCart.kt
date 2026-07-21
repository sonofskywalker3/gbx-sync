package com.jeff.gbxsync.cart

import com.jeff.gbxsync.protocol.GbxDevice
import com.jeff.gbxsync.protocol.GbxException

/**
 * GB/GBC save access. The host drives the MBC directly: RAM enable is a bus
 * write of 0x0A to 0x0000, bank select a write to 0x4000 (MBC1 additionally
 * needs mode 1 via 0x6000). Data moves through the 0xA000-0xBFFF window in
 * 0x200 blocks, 64-byte serial chunks (FlashGBX's proven-safe sizes for the
 * stock v1.4).
 */
class DmgCart(private val device: GbxDevice) {

    fun readHeader(): DmgHeader = DmgHeader.parse(device.readRom(0, DmgHeader.HEADER_LENGTH))

    fun readSave(saveType: DmgSaveType, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        require(saveType.sizeBytes > 0) { "Cart has no save RAM" }
        val out = ByteArray(saveType.sizeBytes)
        withRamEnabled(saveType.mbc) {
            var written = 0
            for (bank in 0 until saveType.bankCount) {
                selectRamBank(saveType.mbc, bank)
                var pos = 0
                while (pos < saveType.bytesPerBank) {
                    val blockLen = minOf(BLOCK_LEN, saveType.bytesPerBank - pos)
                    val block = retryBlock { device.readRam(pos.toLong(), blockLen, maxChunk = CHUNK_LEN) }
                    System.arraycopy(block, 0, out, written, blockLen)
                    written += blockLen
                    pos += blockLen
                    progress(written, out.size)
                }
            }
        }
        if (saveType.mbc == Mbc.MBC2) {
            // MBC2 RAM is 4-bit; upper nibble is open bus.
            for (i in out.indices) out[i] = (out[i].toInt() and 0x0F).toByte()
        }
        return out
    }

    fun writeSave(saveType: DmgSaveType, data: ByteArray, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(saveType.sizeBytes > 0) { "Cart has no save RAM" }
        require(data.size >= saveType.sizeBytes) {
            "Save file too small: ${data.size} < ${saveType.sizeBytes}"
        }
        withRamEnabled(saveType.mbc) {
            var written = 0
            for (bank in 0 until saveType.bankCount) {
                selectRamBank(saveType.mbc, bank)
                var pos = 0
                while (pos < saveType.bytesPerBank) {
                    val blockLen = minOf(BLOCK_LEN, saveType.bytesPerBank - pos)
                    val block = data.copyOfRange(written, written + blockLen)
                    device.writeRam(pos.toLong(), block, maxChunk = WRITE_CHUNK_LEN)
                    written += blockLen
                    pos += blockLen
                    progress(written, saveType.sizeBytes)
                }
            }
        }
    }

    /** Re-reads the save and compares against [expected]; returns true on match. */
    fun verifySave(saveType: DmgSaveType, expected: ByteArray): Boolean {
        val actual = readSave(saveType)
        if (saveType.mbc == Mbc.MBC2) {
            return actual.indices.all {
                (actual[it].toInt() and 0x0F) == (expected[it].toInt() and 0x0F)
            }
        }
        return actual.contentEquals(expected.copyOf(actual.size))
    }

    private fun withRamEnabled(mbc: Mbc, body: () -> Unit) {
        device.mbcReset()
        enableRam(mbc, true)
        try {
            body()
        } finally {
            try {
                enableRam(mbc, false)
                device.mbcReset()
            } catch (_: GbxException) {
                // Best effort: leaving RAM enabled is recoverable on next power cycle.
            }
        }
    }

    private fun enableRam(mbc: Mbc, enable: Boolean) {
        when (mbc) {
            Mbc.MBC1 -> if (enable) {
                device.cartWrite(0x6000, 0x01)
                device.cartWrite(0x0000, 0x0A)
            } else {
                device.cartWrite(0x0000, 0x00)
                device.cartWrite(0x6000, 0x00)
            }
            else -> device.cartWrite(0x0000, if (enable) 0x0A else 0x00)
        }
    }

    private fun selectRamBank(mbc: Mbc, bank: Int) {
        if (mbc == Mbc.MBC2) return // no banking
        device.cartWrite(0x4000, bank and 0xFF)
    }

    private fun <T> retryBlock(attempts: Int = 3, body: () -> T): T {
        var last: GbxException? = null
        repeat(attempts) {
            try {
                return body()
            } catch (e: GbxException) {
                last = e
            }
        }
        throw last!!
    }

    companion object {
        const val BLOCK_LEN = 0x200
        const val CHUNK_LEN = 64
        const val WRITE_CHUNK_LEN = 0x100
    }
}
