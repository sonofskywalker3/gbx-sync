package com.jeff.gbxsync

import com.jeff.gbxsync.serial.GbxTransport

/** Scriptable transport: records writes, plays back queued responses. */
class FakeTransport : GbxTransport {
    val written = mutableListOf<ByteArray>()
    private val responses = ArrayDeque<Byte>()

    fun queue(vararg bytes: Int) {
        bytes.forEach { responses.addLast(it.toByte()) }
    }

    fun queue(bytes: ByteArray) {
        bytes.forEach { responses.addLast(it) }
    }

    val allWritten: ByteArray
        get() = written.fold(ByteArray(0)) { acc, b -> acc + b }

    override fun write(data: ByteArray) {
        written += data
    }

    override fun readExact(count: Int, timeoutMs: Int): ByteArray? {
        if (responses.size < count) return null
        return ByteArray(count) { responses.removeFirst() }
    }

    override fun purge() {}
    override fun close() {}
}
