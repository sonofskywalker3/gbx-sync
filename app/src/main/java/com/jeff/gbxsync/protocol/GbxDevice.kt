package com.jeff.gbxsync.protocol

import com.jeff.gbxsync.serial.GbxTransport

/**
 * Protocol driver for the GBxCart RW v1.4 running modern firmware.
 *
 * Model: most state (target address, transfer size, DMG access mode, …) is
 * pushed into firmware variables with SET_VARIABLE; bulk commands then move
 * whole buffers. The firmware auto-increments its address across consecutive
 * bulk commands, so a large transfer is one variable setup plus N identical
 * one-byte commands. Write bursts are acknowledged with 0x01/0x03 (0x02 = error).
 */
class GbxDevice(private val transport: GbxTransport) {

    var firmware: FirmwareInfo? = null
        private set
    var mode: CartMode? = null
        private set

    private val ackWait get() = (firmware?.fwVer ?: 0) >= 12

    // ---- low level ---------------------------------------------------------

    private fun writeBytes(bytes: ByteArray) = transport.write(bytes)

    private fun sendCommand(cmd: Int, wait: Boolean = false) {
        writeBytes(byteArrayOf(cmd.toByte()))
        if (wait) waitForAck("command 0x%02X".format(cmd))
    }

    private fun waitForAck(context: String) {
        val b = transport.readExact(1, ACK_TIMEOUT_MS)
            ?: throw GbxException("Timeout waiting for ack after $context")
        val v = b[0].toInt() and 0xFF
        if (v != 0x01 && v != 0x03) {
            throw GbxException("Device reported error 0x%02X after %s".format(v, context))
        }
    }

    fun setVariable(v: FwVar, value: Long) {
        val buf = ByteArray(10)
        buf[0] = Cmd.SET_VARIABLE.toByte()
        buf[1] = v.widthBytes.toByte()
        putU32(buf, 2, v.key.toLong())
        putU32(buf, 6, value)
        if (ackWait) tryWrite(buf) else writeBytes(buf)
    }

    fun getVariable(v: FwVar): Long {
        val buf = ByteArray(6)
        buf[0] = Cmd.GET_VARIABLE.toByte()
        buf[1] = v.widthBytes.toByte()
        putU32(buf, 2, v.key.toLong())
        writeBytes(buf)
        val resp = transport.readExact(4, READ_TIMEOUT_MS)
            ?: throw GbxException("Timeout reading variable ${v.name}")
        return u32(resp, 0)
    }

    /** Write with ack, retrying with a resync (0x00 ping) like FlashGBX's _try_write. */
    private fun tryWrite(data: ByteArray, retries: Int = 5) {
        var lastError: GbxException? = null
        repeat(retries) {
            try {
                writeBytes(data)
                waitForAck("packet 0x%02X".format(data[0].toInt() and 0xFF))
                return
            } catch (e: GbxException) {
                lastError = e
                transport.purge()
                writeBytes(byteArrayOf(0x00))
                transport.readExact(1, 250)
            }
        }
        throw lastError ?: GbxException("Write failed")
    }

    // ---- connect / detect --------------------------------------------------

    /**
     * Queries PCB revision and firmware info. Mirrors FlashGBX
     * LoadFirmwareVersion(): 0x68 → pcb, 0x56 → ofw, then QUERY_FW_INFO which
     * answers [len=8][cfwId:char][fwVer:u16][pcb:u8][ts:u32] plus, on L≥12,
     * [len][name][features][bootloader].
     */
    fun connect(): FirmwareInfo {
        transport.purge()
        sendCommand(Cmd.OFW_PCB_VER)
        val pcb = (transport.readExact(1, 300) ?: throw GbxException(
            "No response from device — is this a GBxCart RW?"
        ))[0].toInt() and 0xFF

        sendCommand(Cmd.OFW_FW_VER)
        val ofw = (transport.readExact(1, 300)
            ?: throw GbxException("No firmware version response"))[0].toInt() and 0xFF

        if ((pcb == 2 && ofw == 2) || (pcb >= 5 && ofw == 0)) {
            throw GbxException("Device did not identify as a GBxCart RW")
        }
        if (pcb < 5) {
            throw GbxException(
                "GBxCart RW v1.3 or older detected — this app supports v1.4 only"
            )
        }

        sendCommand(Cmd.QUERY_FW_INFO)
        val size = (transport.readExact(1, 500)
            ?: throw GbxException("No QUERY_FW_INFO response"))[0].toInt() and 0xFF
        if (size != 8) throw GbxException("Unexpected QUERY_FW_INFO size $size")
        val info = transport.readExact(8, 500)
            ?: throw GbxException("Short QUERY_FW_INFO response")

        val cfwId = (info[0].toInt() and 0xFF).toChar().toString()
        val fwVer = ((info[1].toInt() and 0xFF) shl 8) or (info[2].toInt() and 0xFF)
        val pcbVer = info[3].toInt() and 0xFF
        val fwTs = u32(info, 4)

        var pcbName = ""
        var cartPowerCtrl = false
        if (cfwId == "L" && fwVer >= 12) {
            val nameLen = (transport.readExact(1, 500)
                ?: throw GbxException("Short firmware name"))[0].toInt() and 0xFF
            if (nameLen > 0) {
                val name = transport.readExact(nameLen, 500)
                    ?: throw GbxException("Short firmware name")
                pcbName = String(name, Charsets.UTF_8).replace("\u0000", "").trim()
            }
            val features = (transport.readExact(1, 500)
                ?: throw GbxException("Short feature flags"))[0].toInt() and 0xFF
            cartPowerCtrl = features and 1 == 1
            transport.readExact(1, 500) // bootloader-reset flag, unused
        }

        val fw = FirmwareInfo(
            cfwId = cfwId,
            fwVer = fwVer,
            pcbVer = pcbVer,
            fwTimestamp = fwTs,
            ofwVer = ofw,
            pcbName = pcbName.ifEmpty { "GBxCart RW" },
            cartPowerCtrl = cartPowerCtrl,
        )
        firmware = fw
        return fw
    }

    fun ping(): Boolean {
        return try {
            sendCommand(Cmd.PING, wait = true)
            true
        } catch (_: GbxException) {
            false
        }
    }

    // ---- mode & power ------------------------------------------------------

    /** SetMode(): mode + voltage + read-method vars, then cart power-on. */
    fun setMode(newMode: CartMode) {
        if (mode != null) {
            cartPowerOff()
            Thread.sleep(100)
        }
        when (newMode) {
            CartMode.DMG -> {
                sendCommand(Cmd.SET_MODE_DMG, wait = ackWait)
                sendCommand(Cmd.SET_VOLTAGE_5V, wait = ackWait)
                setVariable(FwVar.DMG_READ_METHOD, 1) // "A15" method, FlashGBX default
                setVariable(FwVar.CART_MODE, 1)
            }
            CartMode.AGB -> {
                sendCommand(Cmd.SET_MODE_AGB, wait = ackWait)
                sendCommand(Cmd.SET_VOLTAGE_3_3V, wait = ackWait)
                setVariable(FwVar.AGB_READ_METHOD, 0) // "Single"
                setVariable(FwVar.AGB_IRQ_ENABLED, 0)
                setVariable(FwVar.CART_MODE, 2)
            }
        }
        setVariable(FwVar.ADDRESS, 0)
        mode = newMode
        cartPowerOn()
    }

    private fun cartPowerOn() {
        val fw = firmware ?: throw GbxException("Not connected")
        if (fw.cartPowerCtrl) {
            sendCommand(Cmd.QUERY_CART_PWR)
            val powered = transport.readExact(1, READ_TIMEOUT_MS)
                ?: throw GbxException("No cart power status")
            if (powered[0].toInt() == 0) {
                if (fw.pcbName == "GBxCart RW") {
                    // FlashGBX workaround: power-on can glitch; poll for the ack.
                    sendCommand(Cmd.CART_PWR_ON)
                    Thread.sleep(200)
                    var ok = false
                    repeat(10) {
                        if (ok) return@repeat
                        val b = transport.readExact(1, 150)
                        if (b != null && b[0].toInt() == 1) {
                            ok = true
                        } else {
                            sendCommand(Cmd.QUERY_CART_PWR)
                            Thread.sleep(50)
                        }
                    }
                    if (!ok) throw GbxException("Couldn't power on the cartridge")
                } else {
                    sendCommand(Cmd.CART_PWR_ON, wait = true)
                }
            }
        }
        when (mode) {
            CartMode.DMG -> sendCommand(Cmd.DMG_MBC_RESET, wait = ackWait)
            CartMode.AGB -> sendCommand(Cmd.AGB_BOOTUP_SEQUENCE, wait = ackWait)
            null -> {}
        }
    }

    fun cartPowerOff() {
        val fw = firmware ?: return
        if (fw.cartPowerCtrl) {
            sendCommand(Cmd.CART_PWR_OFF, wait = ackWait)
        } else {
            sendCommand(Cmd.SET_ADDR_AS_INPUTS, wait = ackWait)
        }
        mode = null
    }

    // ---- bus primitives ----------------------------------------------------

    /** Single write cycle on the cartridge bus (MBC control, GBA flash commands). */
    fun cartWrite(address: Long, value: Int) {
        val buf: ByteArray
        when (mode) {
            CartMode.DMG -> {
                buf = ByteArray(6)
                buf[0] = Cmd.DMG_CART_WRITE.toByte()
                putU32(buf, 1, address)
                buf[5] = (value and 0xFF).toByte()
            }
            CartMode.AGB -> {
                buf = ByteArray(7)
                buf[0] = Cmd.AGB_CART_WRITE.toByte()
                putU32(buf, 1, address shr 1)
                buf[5] = ((value shr 8) and 0xFF).toByte()
                buf[6] = (value and 0xFF).toByte()
            }
            null -> throw GbxException("Mode not set")
        }
        if (ackWait) tryWrite(buf) else writeBytes(buf)
    }

    /** Bulk ROM read at [address] (byte address for DMG, halfword-shifted internally for AGB). */
    fun readRom(address: Long, length: Int, progress: (Int) -> Unit = {}): ByteArray {
        require(length > 0)
        val chunk = minOf(length, Limits.MAX_BUFFER_READ)
        setVariable(FwVar.TRANSFER_SIZE, chunk.toLong())
        when (mode) {
            CartMode.DMG -> {
                setVariable(FwVar.ADDRESS, address)
                setVariable(FwVar.DMG_ACCESS_MODE, DmgAccessMode.ROM_READ.toLong())
            }
            CartMode.AGB -> setVariable(FwVar.ADDRESS, address shr 1)
            null -> throw GbxException("Mode not set")
        }
        val cmd = byteArrayOf((if (mode == CartMode.DMG) Cmd.DMG_CART_READ else Cmd.AGB_CART_READ).toByte())
        return bulkRead(cmd, length, chunk, progress)
    }

    /**
     * Bulk save-media read. DMG: [address] is an offset in the 0xA000 window.
     * AGB: raw SRAM/flash address, or 8-byte block index for EEPROM.
     * [commandBytes] overrides the bulk command (EEPROM commands carry a
     * parameter byte, e.g. [0xC5, 0x01]).
     */
    fun readRam(
        address: Long,
        length: Int,
        commandBytes: ByteArray? = null,
        maxChunk: Int = Limits.MAX_BUFFER_READ,
        progress: (Int) -> Unit = {},
    ): ByteArray {
        require(length > 0)
        val chunk = minOf(length, maxChunk, Limits.MAX_BUFFER_READ)
        setVariable(FwVar.TRANSFER_SIZE, chunk.toLong())
        val cmd: ByteArray
        when (mode) {
            CartMode.DMG -> {
                setVariable(FwVar.ADDRESS, 0xA000 + address)
                setVariable(FwVar.DMG_ACCESS_MODE, DmgAccessMode.RAM_READ.toLong())
                setVariable(FwVar.DMG_READ_CS_PULSE, 1)
                cmd = commandBytes ?: byteArrayOf(Cmd.DMG_CART_READ.toByte())
            }
            CartMode.AGB -> {
                setVariable(FwVar.ADDRESS, address)
                cmd = commandBytes ?: byteArrayOf(Cmd.AGB_CART_READ_SRAM.toByte())
            }
            null -> throw GbxException("Mode not set")
        }
        try {
            return bulkRead(cmd, length, chunk, progress)
        } finally {
            if (mode == CartMode.DMG) setVariable(FwVar.DMG_READ_CS_PULSE, 0)
        }
    }

    /** Single-byte save-media read (used by the flash chip ID probe). */
    fun readRamByte(address: Long): Int = readRam(address, 1, maxChunk = 1)[0].toInt() and 0xFF

    /** Bulk save-media write; chunks are acknowledged individually. */
    fun writeRam(
        address: Long,
        data: ByteArray,
        commandBytes: ByteArray? = null,
        maxChunk: Int = 0x100,
        progress: (Int) -> Unit = {},
    ) {
        require(data.isNotEmpty())
        val chunk = minOf(data.size, maxChunk, Limits.MAX_BUFFER_WRITE)
        setVariable(FwVar.TRANSFER_SIZE, chunk.toLong())
        val cmd: ByteArray
        when (mode) {
            CartMode.DMG -> {
                setVariable(FwVar.ADDRESS, 0xA000 + address)
                setVariable(FwVar.DMG_ACCESS_MODE, DmgAccessMode.RAM_WRITE.toLong())
                setVariable(FwVar.DMG_WRITE_CS_PULSE, 1)
                cmd = commandBytes ?: byteArrayOf(Cmd.DMG_CART_WRITE_SRAM.toByte())
            }
            CartMode.AGB -> {
                setVariable(FwVar.ADDRESS, address)
                cmd = commandBytes ?: byteArrayOf(Cmd.AGB_CART_WRITE_SRAM.toByte())
            }
            null -> throw GbxException("Mode not set")
        }
        try {
            var offset = 0
            while (offset < data.size) {
                writeBytes(cmd)
                writeBytes(data.copyOfRange(offset, offset + chunk))
                waitForAck("RAM write chunk @0x%X".format(offset))
                offset += chunk
                progress(offset)
            }
        } finally {
            if (mode == CartMode.DMG) {
                setVariable(FwVar.ADDRESS, 0)
                setVariable(FwVar.DMG_WRITE_CS_PULSE, 0)
            }
        }
    }

    /**
     * Batched cartridge-bus writes executed by the firmware in one go
     * (flash unlock/erase/ID sequences). Packet:
     * [0xD4][flashcart:1][count:1] then per entry [addr:u32 BE][value:u16 BE].
     * AGB addresses are halfword-shifted only in flashcart (ROM flash) mode.
     */
    fun cartWriteFlash(commands: List<Pair<Long, Int>>, flashcart: Boolean = false) {
        val buf = ByteArray(3 + commands.size * 6)
        buf[0] = CMD_CART_WRITE_FLASH_CMD.toByte()
        buf[1] = if (flashcart) 1 else 0
        buf[2] = commands.size.toByte()
        commands.forEachIndexed { i, (addr, value) ->
            val at = 3 + i * 6
            val a = if (mode == CartMode.AGB && flashcart) addr shr 1 else addr
            putU32(buf, at, a)
            buf[at + 4] = ((value shr 8) and 0xFF).toByte()
            buf[at + 5] = (value and 0xFF).toByte()
        }
        writeBytes(buf)
        val ret = transport.readExact(1, READ_TIMEOUT_MS)
            ?: throw GbxException("No response to flash command batch")
        if (ret[0].toInt() != 0x01) {
            throw GbxException("Flash command batch failed (0x%02X)".format(ret[0].toInt() and 0xFF))
        }
    }

    fun mbcReset() {
        sendCommand(Cmd.DMG_MBC_RESET, wait = ackWait)
    }

    private fun bulkRead(cmd: ByteArray, length: Int, chunk: Int, progress: (Int) -> Unit): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val want = minOf(chunk, length - offset)
            writeBytes(cmd)
            val part = transport.readExact(want, READ_TIMEOUT_MS)
                ?: throw GbxException("Timeout reading %d bytes at offset 0x%X".format(want, offset))
            System.arraycopy(part, 0, out, offset, want)
            offset += want
            progress(offset)
        }
        return out
    }

    // ---- helpers -----------------------------------------------------------

    private fun putU32(buf: ByteArray, at: Int, value: Long) {
        buf[at] = ((value shr 24) and 0xFF).toByte()
        buf[at + 1] = ((value shr 16) and 0xFF).toByte()
        buf[at + 2] = ((value shr 8) and 0xFF).toByte()
        buf[at + 3] = (value and 0xFF).toByte()
    }

    private fun u32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xFF) shl 24) or
            ((buf[at + 1].toLong() and 0xFF) shl 16) or
            ((buf[at + 2].toLong() and 0xFF) shl 8) or
            (buf[at + 3].toLong() and 0xFF)

    companion object {
        const val READ_TIMEOUT_MS = 2000
        const val ACK_TIMEOUT_MS = 2000
        private const val CMD_CART_WRITE_FLASH_CMD = 0xD4
    }
}

enum class CartMode { DMG, AGB }
