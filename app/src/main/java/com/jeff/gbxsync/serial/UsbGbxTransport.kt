package com.jeff.gbxsync.serial

import com.hoho.android.usbserial.driver.UsbSerialPort

/** GbxTransport over a usb-serial-for-android port (CH340 @ 1 Mbaud). */
class UsbGbxTransport(private val port: UsbSerialPort) : GbxTransport {

    private val readBuffer = ByteArray(16384)

    override fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun readExact(count: Int, timeoutMs: Int): ByteArray? {
        val out = ByteArray(count)
        var have = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (have < count) {
            val remainMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remainMs <= 0) return null
            val n = port.read(readBuffer, minOf(remainMs, 200))
            if (n > 0) {
                val take = minOf(n, count - have)
                System.arraycopy(readBuffer, 0, out, have, take)
                have += take
                // Anything beyond `count` would be protocol desync; drop it.
            }
        }
        return out
    }

    override fun purge() {
        try {
            port.purgeHwBuffers(true, true)
        } catch (_: Exception) {
            // Not all drivers support purge; drain manually instead.
        }
        try {
            while (port.read(readBuffer, 10) > 0) { /* drain */ }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try {
            port.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
