package com.jeff.gbxsync.cart

/**
 * Parsed GB/GBC cartridge header (bytes 0x000-0x17F of the ROM).
 * Also used for ROM files in the library, so parsing is pure.
 */
data class DmgHeader(
    val title: String,
    val cgbFlag: Int,
    val cartType: Int,
    val romSizeCode: Int,
    val ramSizeCode: Int,
    val headerChecksum: Int,
    val headerChecksumValid: Boolean,
    val logoValid: Boolean,
    val globalChecksum: Int,
) {
    val isValid: Boolean get() = logoValid && headerChecksumValid

    companion object {
        const val HEADER_LENGTH = 0x180

        // First 8 bytes of the Nintendo boot logo at 0x104 — enough to
        // discriminate a real cart from open-bus noise.
        private val LOGO_PREFIX = byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66,
            0xCC.toByte(), 0x0D, 0x00, 0x0B,
        )

        fun parse(data: ByteArray): DmgHeader {
            require(data.size >= HEADER_LENGTH) { "Need 0x180 header bytes" }
            val logoValid = (0 until LOGO_PREFIX.size).all { data[0x104 + it] == LOGO_PREFIX[it] }
            var chk = 0
            for (i in 0x134..0x14C) chk = chk - (data[i].toInt() and 0xFF) - 1
            val checksumValid = (chk and 0xFF) == (data[0x14D].toInt() and 0xFF)

            val titleBytes = data.copyOfRange(0x134, 0x144)
            val cgbFlag = data[0x143].toInt() and 0xFF
            // With a CGB flag the title field is only 15 (or 11) bytes.
            val titleLen = if (cgbFlag == 0x80 || cgbFlag == 0xC0) 15 else 16
            val title = titleBytes.take(titleLen)
                .takeWhile { it.toInt() != 0 }
                .map { (it.toInt() and 0xFF).toChar() }
                .joinToString("")
                .filter { it.code in 0x20..0x7E }
                .trim()

            return DmgHeader(
                title = title,
                cgbFlag = cgbFlag,
                cartType = data[0x147].toInt() and 0xFF,
                romSizeCode = data[0x148].toInt() and 0xFF,
                ramSizeCode = data[0x149].toInt() and 0xFF,
                headerChecksum = data[0x14D].toInt() and 0xFF,
                headerChecksumValid = checksumValid,
                logoValid = logoValid,
                globalChecksum = ((data[0x14E].toInt() and 0xFF) shl 8) or (data[0x14F].toInt() and 0xFF),
            )
        }
    }
}

/** Parsed GBA cartridge header (bytes 0x00-0xBF). */
data class AgbHeader(
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val version: Int,
    val fixedByteValid: Boolean,
    val complementValid: Boolean,
) {
    val isValid: Boolean get() = fixedByteValid && complementValid

    companion object {
        const val HEADER_LENGTH = 0x180

        fun parse(data: ByteArray): AgbHeader {
            require(data.size >= 0xC0) { "Need 0xC0 header bytes" }
            var sum = 0
            for (i in 0xA0..0xBC) sum += data[i].toInt() and 0xFF
            val complement = (-(sum + 0x19)) and 0xFF
            val complementValid = complement == (data[0xBD].toInt() and 0xFF)

            fun ascii(from: Int, to: Int) = data.copyOfRange(from, to)
                .takeWhile { it.toInt() != 0 }
                .map { (it.toInt() and 0xFF).toChar() }
                .joinToString("")
                .filter { it.code in 0x20..0x7E }
                .trim()

            return AgbHeader(
                title = ascii(0xA0, 0xAC),
                gameCode = ascii(0xAC, 0xB0),
                makerCode = ascii(0xB0, 0xB2),
                version = data[0xBC].toInt() and 0xFF,
                fixedByteValid = (data[0xB2].toInt() and 0xFF) == 0x96,
                complementValid = complementValid,
            )
        }
    }
}
