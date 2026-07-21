package com.jeff.gbxsync.protocol

/**
 * GBxCart RW v1.4 command set (modern L/R firmware). Byte values verified
 * against FlashGBX's LK_Device.py / hw_GBxCartRW.py.
 */
object Cmd {
    const val OFW_PCB_VER = 0x68
    const val OFW_FW_VER = 0x56

    const val QUERY_FW_INFO = 0xA1
    const val SET_MODE_AGB = 0xA2
    const val SET_MODE_DMG = 0xA3
    const val SET_VOLTAGE_3_3V = 0xA4
    const val SET_VOLTAGE_5V = 0xA5
    const val SET_VARIABLE = 0xA6
    const val SET_ADDR_AS_INPUTS = 0xA8
    const val GET_VARIABLE = 0xAD

    const val DMG_CART_READ = 0xB1
    const val DMG_CART_WRITE = 0xB2
    const val DMG_CART_WRITE_SRAM = 0xB3
    const val DMG_MBC_RESET = 0xB4

    const val AGB_CART_READ = 0xC1
    const val AGB_CART_WRITE = 0xC2
    const val AGB_CART_READ_SRAM = 0xC3
    const val AGB_CART_WRITE_SRAM = 0xC4
    const val AGB_CART_READ_EEPROM = 0xC5
    const val AGB_CART_WRITE_EEPROM = 0xC6
    const val AGB_CART_WRITE_FLASH_DATA = 0xC7
    const val AGB_BOOTUP_SEQUENCE = 0xC9

    const val CART_PWR_ON = 0xF2
    const val CART_PWR_OFF = 0xF3
    const val QUERY_CART_PWR = 0xF4
    const val PING = 0xFE
}

/**
 * Firmware variables set via SET_VARIABLE: [0xA6][width bytes][key u32 BE][value u32 BE].
 * The width byte is 1, 2 or 4 and selects the firmware's 8/16/32-bit variable bank.
 */
enum class FwVar(val widthBytes: Int, val key: Int) {
    ADDRESS(4, 0x00),
    TRANSFER_SIZE(2, 0x00),
    DMG_ROM_BANK(2, 0x02),
    CART_MODE(1, 0x00),
    DMG_ACCESS_MODE(1, 0x01),
    DMG_READ_CS_PULSE(1, 0x08),
    DMG_WRITE_CS_PULSE(1, 0x09),
    DMG_READ_METHOD(1, 0x0B),
    AGB_READ_METHOD(1, 0x0C),
    AGB_IRQ_ENABLED(1, 0x10),
}

object DmgAccessMode {
    const val ROM_READ = 1
    const val RAM_READ = 3
    const val RAM_WRITE = 4
}

object Limits {
    const val MAX_BUFFER_READ = 0x1000
    const val MAX_BUFFER_WRITE = 0x400
}

class GbxException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class FirmwareInfo(
    val cfwId: String,
    val fwVer: Int,
    val pcbVer: Int,
    val fwTimestamp: Long,
    val ofwVer: Int,
    val pcbName: String,
    val cartPowerCtrl: Boolean,
) {
    val pcbLabel: String
        get() = when (pcbVer) {
            5 -> "v1.4"
            6 -> "v1.4a/b/c"
            2 -> "v1.1/v1.2"
            4 -> "v1.3"
            90 -> "XMAS v1.0"
            100, 101 -> "Mini v1.0"
            else -> "PCB $pcbVer"
        }

    /** The app implements only the modern command set (ack-capable firmware). */
    val isSupported: Boolean get() = pcbVer >= 5 && fwVer >= 12
}
