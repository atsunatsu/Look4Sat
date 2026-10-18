package com.rtbishop.look4sat.core.data.framework.transport

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Android USB host / OTG byte stream via usb-serial-for-android.
 * Persistent device IDs use VID:PID. Multiple identical VID/PID devices
 * cannot be distinguished by this initial implementation.
 */
class UsbSerialChannel(
    private val context: Context,
    private val deviceId: String,
    private val baudRate: Int
) : SerialChannel {
    private val incoming = ByteQueueInputStream()
    private val running = AtomicBoolean(false)
    private var port: UsbSerialPort? = null
    private var readerThread: Thread? = null

    override val inputStream: InputStream = incoming
    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
            if (!running.get()) throw IOException("USB serial disconnected")
            if (len > 0) synchronized(this@UsbSerialChannel) {
                port?.write(bytes.copyOfRange(off, off + len), 2500)
                    ?: throw IOException("USB serial port unavailable")
            }
        }
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext true
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull {
            "${it.device.vendorId}:${it.device.productId}" == deviceId
        } ?: return@withContext false
        val device = driver.device
        if (!manager.hasPermission(device)) {
            val (requestId, approved) = UsbPermissionReceiver.register()
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestId, UsbPermissionReceiver.makeIntent(context, requestId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                manager.requestPermission(device, pendingIntent)
                if (withTimeoutOrNull(25_000) { approved.await() } != true) return@withContext false
            } finally {
                UsbPermissionReceiver.unregister(requestId)
            }
        }
        val connection = manager.openDevice(device) ?: return@withContext false
        val newPort = driver.ports.firstOrNull() ?: run {
            connection.close()
            return@withContext false
        }
        try {
            newPort.open(connection)
            newPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = newPort
            running.set(true)
            readerThread = thread(name = "Look4Sat-USB-CAT", isDaemon = true) {
                val buffer = ByteArray(512)
                while (running.get()) {
                    try {
                        val count = newPort.read(buffer, 200)
                        if (count > 0) incoming.accept(buffer, count)
                    } catch (_: Exception) {
                        running.set(false)
                        incoming.close()
                        break
                    }
                }
            }
            true
        } catch (_: Exception) {
            try { newPort.close() } catch (_: Exception) {}
            connection.close()
            port = null
            false
        }
    }

    override fun close() {
        running.set(false)
        incoming.close()
        readerThread?.interrupt()
        try { port?.close() } catch (_: Exception) {}
        port = null
    }
}
