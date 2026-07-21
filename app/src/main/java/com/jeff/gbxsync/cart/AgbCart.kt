package com.jeff.gbxsync.cart

import com.jeff.gbxsync.protocol.Cmd
import com.jeff.gbxsync.protocol.GbxDevice
import com.jeff.gbxsync.protocol.GbxException

/**
 * GBA save access: SRAM/FRAM, flash (64K/128K, non-Atmel) and EEPROM (4K/64K).
 * Save type is detected empirically the way FlashGBX does it: flash chip ID
 * probe first, then SRAM mirror detection, then the EEPROM cross-read test.
 */
class AgbCart(private val device: GbxDevice) {

    fun readHeader(): AgbHeader = AgbHeader.parse(device.readRom(0, AgbHeader.HEADER_LENGTH))

    // ---- detection ---------------------------------------------------------

    data class Detection(val saveType: AgbSaveType, val flashChipId: Int?)

    fun detectSaveType(): Detection {
        val flashChip = readFlashSaveId()
        if (flashChip != null) {
            if (AgbFlashChips.isAtmel(flashChip)) {
                throw GbxException("Atmel save flash chip is not supported yet")
            }
            val size = AgbFlashChips.sizeOf(flashChip)
            return when (size) {
                65536 -> Detection(AgbSaveType.FLASH_512K, flashChip)
                131072 -> Detection(AgbSaveType.FLASH_1M, flashChip)
                else -> Detection(AgbSaveType.NONE, flashChip)
            }
        }

        // SRAM sizing via mirror detection on a banked 128 KB read (the SRAM
        // window is 64 KB; bootleg carts bank-switch via a write to 0x1000000).
        val sram = ByteArray(131072)
        for (bank in 0..1) {
            device.cartWrite(0x1000000, bank)
            val part = device.readRam(0, 65536, maxChunk = CHUNK_LEN)
            System.arraycopy(part, 0, sram, bank * 65536, 65536)
        }
        device.cartWrite(0x1000000, 0)
        val repeat = findRepeatingSize(sram)
        if (repeat > 256) {
            return when (repeat) {
                131072 -> Detection(AgbSaveType.SRAM_1M, null)
                65536 -> Detection(AgbSaveType.SRAM_512K, null)
                else -> Detection(AgbSaveType.SRAM_256K, null)
            }
        }

        // EEPROM: read through both address widths and compare.
        val eeprom4k = readEepromRaw(AgbSaveType.EEPROM_4K)
        val eeprom64k = readEepromRaw(AgbSaveType.EEPROM_64K)
        val blank = eeprom64k.all { it == 0xFF.toByte() } || eeprom64k.all { it.toInt() == 0 }
        return when {
            blank -> Detection(AgbSaveType.NONE, null)
            eeprom4k.contentEquals(eeprom64k.copyOf(eeprom4k.size)) ->
                Detection(AgbSaveType.EEPROM_64K, null)
            else -> Detection(AgbSaveType.EEPROM_4K, null)
        }
    }

    /**
     * Flash chip ID probe. First proves the medium is flash (SRAM would let a
     * plain byte write stick; flash ignores it), then issues the 0x90 ID
     * command. Returns null for SRAM/no flash.
     */
    private fun readFlashSaveId(): Int? {
        val before = device.readRam(0, 0x10, maxChunk = 0x10)
        val test1 = before[4].toInt() and 0xFF
        device.cartWriteFlash(listOf(0x0004L to (test1.inv() and 0xFF)))
        val after = device.readRam(0, 0x10, maxChunk = 0x10)
        val test2 = after[4].toInt() and 0xFF
        device.cartWriteFlash(listOf(0x0004L to test1))
        if (test1 != test2) return null // it's SRAM/FRAM

        val old5555 = device.readRamByte(0x5555)
        val old2AAA = device.readRamByte(0x2AAA)
        val old0000 = device.readRamByte(0x0000)

        device.cartWriteFlash(FLASH_UNLOCK + (0x5555L to 0x90))
        val id = device.readRam(0, 2, maxChunk = 2)
        val chipId = ((id[0].toInt() and 0xFF) shl 8) or (id[1].toInt() and 0xFF)
        device.cartWriteFlash(FLASH_UNLOCK + (0x5555L to 0xF0))
        Thread.sleep(10)
        device.cartWriteFlash(listOf(0L to 0xF0))
        Thread.sleep(10)

        if (AgbFlashChips.sizeOf(chipId) == null) {
            // Unknown ID — restore whatever SRAM bytes we may have poked.
            device.cartWriteFlash(
                listOf(0x5555L to old5555, 0x2AAAL to old2AAA, 0x0000L to old0000)
            )
            return null
        }
        return chipId
    }

    // ---- read --------------------------------------------------------------

    fun readSave(saveType: AgbSaveType, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        return when {
            saveType.isEeprom -> readEepromRaw(saveType, progress)
            saveType.isSram || saveType.isFlash -> {
                val size = saveType.sizeBytes
                val out = ByteArray(size)
                val bankSize = 0x10000
                val banks = (size + bankSize - 1) / bankSize
                var written = 0
                for (bank in 0 until banks) {
                    if (banks > 1) switchBank(saveType, bank)
                    val bankBytes = minOf(bankSize, size - bank * bankSize)
                    var pos = 0
                    while (pos < bankBytes) {
                        val blockLen = minOf(BLOCK_LEN, bankBytes - pos)
                        val block = retryBlock {
                            device.readRam(pos.toLong(), blockLen, maxChunk = CHUNK_LEN)
                        }
                        System.arraycopy(block, 0, out, written, blockLen)
                        written += blockLen
                        pos += blockLen
                        progress(written, size)
                    }
                }
                if (banks > 1) switchBank(saveType, 0)
                out
            }
            else -> throw GbxException("Cart has no save data")
        }
    }

    private fun readEepromRaw(
        saveType: AgbSaveType,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): ByteArray {
        val size = saveType.sizeBytes
        val cmd = byteArrayOf(
            Cmd.AGB_CART_READ_EEPROM.toByte(),
            (if (saveType == AgbSaveType.EEPROM_4K) 1 else 2).toByte(),
        )
        val out = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val blockLen = minOf(EEPROM_READ_BLOCK, size - pos)
            val block = retryBlock {
                device.readRam((pos / 8).toLong(), blockLen, commandBytes = cmd, maxChunk = CHUNK_LEN)
            }
            System.arraycopy(block, 0, out, pos, blockLen)
            pos += blockLen
            progress(pos, size)
        }
        return out
    }

    // ---- write -------------------------------------------------------------

    fun writeSave(
        saveType: AgbSaveType,
        data: ByteArray,
        flashChipId: Int?,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        require(data.size >= saveType.sizeBytes) {
            "Save file too small: ${data.size} < ${saveType.sizeBytes}"
        }
        when {
            saveType.isEeprom -> writeEeprom(saveType, data, progress)
            saveType.isSram -> writeSram(saveType, data, progress)
            saveType.isFlash -> writeFlash(saveType, data, flashChipId, progress)
            else -> throw GbxException("Cart has no save data")
        }
    }

    private fun writeEeprom(saveType: AgbSaveType, data: ByteArray, progress: (Int, Int) -> Unit) {
        val size = saveType.sizeBytes
        val cmd = byteArrayOf(
            Cmd.AGB_CART_WRITE_EEPROM.toByte(),
            (if (saveType == AgbSaveType.EEPROM_4K) 1 else 2).toByte(),
        )
        var pos = 0
        while (pos < size) {
            val blockLen = minOf(EEPROM_WRITE_BLOCK, size - pos)
            device.writeRam(
                (pos / 8).toLong(),
                data.copyOfRange(pos, pos + blockLen),
                commandBytes = cmd,
                maxChunk = blockLen,
            )
            pos += blockLen
            progress(pos, size)
        }
    }

    private fun writeSram(saveType: AgbSaveType, data: ByteArray, progress: (Int, Int) -> Unit) {
        val size = saveType.sizeBytes
        val bankSize = 0x10000
        val banks = (size + bankSize - 1) / bankSize
        var written = 0
        for (bank in 0 until banks) {
            if (banks > 1) switchBank(saveType, bank)
            val bankBytes = minOf(bankSize, size - bank * bankSize)
            var pos = 0
            while (pos < bankBytes) {
                val blockLen = minOf(BLOCK_LEN, bankBytes - pos)
                device.writeRam(
                    pos.toLong(),
                    data.copyOfRange(written, written + blockLen),
                    maxChunk = WRITE_CHUNK_LEN,
                )
                written += blockLen
                pos += blockLen
                progress(written, size)
            }
        }
        if (banks > 1) switchBank(saveType, 0)
    }

    private fun writeFlash(
        saveType: AgbSaveType,
        data: ByteArray,
        flashChipId: Int?,
        progress: (Int, Int) -> Unit,
    ) {
        if (flashChipId != null && AgbFlashChips.isAtmel(flashChipId)) {
            throw GbxException("Atmel save flash chip is not supported yet")
        }
        val size = saveType.sizeBytes
        val bankSize = 0x10000
        val banks = (size + bankSize - 1) / bankSize
        val writeCmd = byteArrayOf(Cmd.AGB_CART_WRITE_FLASH_DATA.toByte(), 1)
        var written = 0
        for (bank in 0 until banks) {
            if (banks > 1) switchFlashBank(bank)
            val bankBytes = minOf(bankSize, size - bank * bankSize)
            var pos = 0
            while (pos < bankBytes) {
                val blockLen = minOf(FLASH_SECTOR, bankBytes - pos)
                eraseFlashSector(pos.toLong())
                val block = data.copyOfRange(written, written + blockLen)
                if (!block.all { it == 0xFF.toByte() }) {
                    device.writeRam(pos.toLong(), block, commandBytes = writeCmd, maxChunk = WRITE_CHUNK_LEN)
                }
                written += blockLen
                pos += blockLen
                progress(written, size)
            }
        }
        if (banks > 1) switchFlashBank(0)
    }

    private fun eraseFlashSector(sectorAddress: Long) {
        device.cartWriteFlash(
            FLASH_UNLOCK + (0x5555L to 0x80) + FLASH_UNLOCK + (sectorAddress to 0x30)
        )
        var lives = 50
        while (true) {
            Thread.sleep(10)
            val sr = device.readRam(sectorAddress, 2, maxChunk = 2)
            if (sr[0] == 0xFF.toByte() && sr[1] == 0xFF.toByte()) break
            if (--lives == 0) {
                throw GbxException("Flash sector at 0x%X did not erase".format(sectorAddress))
            }
        }
    }

    private fun switchBank(saveType: AgbSaveType, bank: Int) {
        when {
            saveType == AgbSaveType.SRAM_1M || saveType == AgbSaveType.SRAM_512K ->
                device.cartWrite(0x1000000, bank) // bootleg SRAM banking
            saveType == AgbSaveType.FLASH_1M -> switchFlashBank(bank)
            else -> {}
        }
    }

    private fun switchFlashBank(bank: Int) {
        device.cartWriteFlash(FLASH_UNLOCK + (0x5555L to 0xB0) + (0L to bank))
        Thread.sleep(50)
    }

    private fun findRepeatingSize(data: ByteArray, minSize: Int = 0x20): Int {
        var offset = data.size
        while (offset >= minSize) {
            offset /= 2
            val a = data.copyOfRange(0, offset)
            val b = data.copyOfRange(offset, offset * 2)
            if (!a.contentEquals(b)) {
                offset *= 2
                break
            }
        }
        return offset
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
        val FLASH_UNLOCK = listOf(0x5555L to 0xAA, 0x2AAAL to 0x55)
        const val BLOCK_LEN = 0x1000
        const val CHUNK_LEN = 64
        const val WRITE_CHUNK_LEN = 0x100
        const val EEPROM_READ_BLOCK = 0x100
        const val EEPROM_WRITE_BLOCK = 0x40
        const val FLASH_SECTOR = 0x1000
    }
}
