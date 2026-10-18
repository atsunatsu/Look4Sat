package com.rtbishop.look4sat.core.data.framework.transport

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Byte-stream transport for radio CAT. Connection methods must not run on the UI thread. */
interface SerialChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
    suspend fun connect(): Boolean
    fun close()
}

/** Also supports InputStream.available(), required by IC-705's CI-V response collector. */
internal class ByteQueueInputStream : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>(65536)
    private val closed = AtomicBoolean(false)

    fun accept(chunk: ByteArray, size: Int = chunk.size) {
        if (closed.get()) return
        for (i in 0 until size.coerceAtMost(chunk.size)) {
            if (!bytes.offer(chunk[i].toInt() and 0xFF)) {
                bytes.poll()
                bytes.offer(chunk[i].toInt() and 0xFF)
            }
        }
    }

    override fun available(): Int = bytes.size

    override fun read(): Int {
        while (true) {
            val value = bytes.poll(150, TimeUnit.MILLISECONDS)
            if (value != null) return value
            if (closed.get()) return -1
        }
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > target.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1
        target[off] = first.toByte()
        var count = 1
        while (count < len) {
            val value = bytes.poll() ?: break
            target[off + count++] = value.toByte()
        }
        return count
    }

    override fun close() {
        closed.set(true)
        bytes.clear()
    }
}
