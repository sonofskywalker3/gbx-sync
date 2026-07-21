package com.jeff.gbxsync.cart

/** GB/GBC memory bank controllers this app can drive for save access. */
enum class Mbc {
    NONE, MBC1, MBC2, MBC3, MBC5,
    UNSUPPORTED; // MBC6/MBC7/TAMA5/HuC/GB-Memory/camera etc. — detected, not dumped

    companion object {
        /** Maps header byte 0x147. */
        fun fromCartType(cartType: Int): Mbc = when (cartType) {
            0x00, 0x08, 0x09 -> NONE
            0x01, 0x02, 0x03 -> MBC1
            0x05, 0x06 -> MBC2
            0x0F, 0x10, 0x11, 0x12, 0x13 -> MBC3
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> MBC5
            else -> UNSUPPORTED
        }
    }
}

data class DmgSaveType(
    val mbc: Mbc,
    val sizeBytes: Int,
) {
    val bankCount: Int get() = if (sizeBytes <= BANK_SIZE) 1 else sizeBytes / BANK_SIZE
    val bytesPerBank: Int get() = minOf(sizeBytes, BANK_SIZE)

    companion object {
        const val BANK_SIZE = 0x2000

        /** Header byte 0x149 → RAM size. */
        fun ramSizeFromCode(code: Int): Int = when (code) {
            0x01 -> 2048
            0x02 -> 8192
            0x03 -> 32768
            0x04 -> 131072
            0x05 -> 65536
            else -> 0
        }

        fun fromHeader(header: DmgHeader): DmgSaveType? {
            val mbc = Mbc.fromCartType(header.cartType)
            if (mbc == Mbc.UNSUPPORTED) return null
            // MBC2 has internal 512x4-bit RAM regardless of the RAM size byte.
            val size = if (mbc == Mbc.MBC2) 512 else ramSizeFromCode(header.ramSizeCode)
            if (size == 0) return DmgSaveType(mbc, 0)
            return DmgSaveType(mbc, size)
        }
    }
}

enum class AgbSaveType(val sizeBytes: Int, val label: String) {
    NONE(0, "None"),
    EEPROM_4K(512, "4K EEPROM"),
    EEPROM_64K(8192, "64K EEPROM"),
    SRAM_256K(32768, "256K SRAM/FRAM"),
    FLASH_512K(65536, "512K FLASH"),
    FLASH_1M(131072, "1M FLASH"),
    SRAM_512K(65536, "512K SRAM"),
    SRAM_1M(131072, "1M SRAM");

    val isEeprom get() = this == EEPROM_4K || this == EEPROM_64K
    val isFlash get() = this == FLASH_512K || this == FLASH_1M
    val isSram get() = this == SRAM_256K || this == SRAM_512K || this == SRAM_1M
}

/** GBA save flash chip IDs (device word read after the 0x90 ID command). */
object AgbFlashChips {
    // chip id -> (size bytes, atmel page-programming quirk)
    private val chips: Map<Int, Pair<Int, Boolean>> = mapOf(
        0xBFD4 to (65536 to false),  // SST 39VF512
        0x1F3D to (65536 to true),   // Atmel AT29LV512 — page mode, unsupported in v1
        0xC21C to (65536 to false),  // Macronix MX29L512
        0x321B to (65536 to false),  // Panasonic MN63F805MNP
        0xC209 to (131072 to false), // Macronix MX29L010
        0x6213 to (131072 to false), // SANYO LE26FV10N1TS
        0xBF4B to (131072 to false), // SST bootleg
        0xBF5B to (131072 to false), // SST bootleg
        0xBF6D to (131072 to false), // SST bootleg
        0xFFFF to (131072 to false), // bootleg fallback
    )

    fun sizeOf(chipId: Int): Int? = chips[chipId]?.first
    fun isAtmel(chipId: Int): Boolean = chips[chipId]?.second == true
}
