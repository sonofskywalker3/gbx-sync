package com.jeff.gbxsync.serial

/**
 * Byte-level link to the GBxCart RW. The protocol layer only ever needs
 * "send these bytes" and "give me exactly N bytes or fail".
 */
interface GbxTransport {
    fun write(data: ByteArray)

    /**
     * Reads exactly [count] bytes, waiting up to [timeoutMs]. Returns null if
     * the full count did not arrive in time (partial data is discarded).
     */
    fun readExact(count: Int, timeoutMs: Int): ByteArray?

    /** Discards any buffered input/output. */
    fun purge()

    fun close()
}
