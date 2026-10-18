package com.rtbishop.look4sat.core.data.framework.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** GATT byte-stream bridge for Nordic UART (NUS) and HM-10 FFE0/FFE1. */
class BleUartChannel(private val context: Context, private val address: String) : SerialChannel {
    private val incoming = ByteQueueInputStream()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var openResult: CompletableDeferred<Boolean>? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeSucceeded = false
    @Volatile private var connected = false
    @Volatile private var payloadSize = 20
    override val inputStream: InputStream = incoming
    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            synchronized(this@BleUartChannel) {
                val client = gatt ?: throw IOException("BLE disconnected")
                val characteristic = writeCharacteristic ?: throw IOException("BLE UART service unavailable")
                if (!connected) throw IOException("BLE disconnected")
                var offset = off
                while (offset < off + len) {
                    val chunk = b.copyOfRange(offset, (offset + payloadSize).coerceAtMost(off + len))
                    val noResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                        (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                    val mode = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val latch = CountDownLatch(1)
                    writeSucceeded = false
                    writeLatch = latch
                    @Suppress("DEPRECATION")
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        client.writeCharacteristic(characteristic, chunk, mode) == BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.writeType = mode
                        characteristic.value = chunk
                        client.writeCharacteristic(characteristic)
                    }
                    if (!started) {
                        writeLatch = null
                        throw IOException("BLE write not submitted")
                    }
                    if (noResponse) Thread.sleep(20) else {
                        if (!latch.await(3000, TimeUnit.MILLISECONDS) || !writeSucceeded) {
                            writeLatch = null
                            throw IOException("BLE write timed out or failed")
                        }
                    }
                    writeLatch = null
                    offset += chunk.size
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!client.discoverServices()) openResult?.let { if (!it.isCompleted) it.complete(false) }
            } else {
                connected = false
                openResult?.let { if (!it.isCompleted) it.complete(false) }
                writeLatch?.countDown()
                incoming.close()
            }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                openResult?.let { if (!it.isCompleted) it.complete(false) }
                return
            }
            val nus = client.getService(NUS_SERVICE)
            val hm10 = client.getService(HM10_SERVICE)
            val write = nus?.getCharacteristic(NUS_WRITE) ?: hm10?.getCharacteristic(HM10_DATA)
            val notify = nus?.getCharacteristic(NUS_NOTIFY) ?: hm10?.getCharacteristic(HM10_DATA)
            if (write == null || notify == null ||
                (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 ||
                (write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0 ||
                !client.setCharacteristicNotification(notify, true)) {
                openResult?.let { if (!it.isCompleted) it.complete(false) }
                return
            }
            writeCharacteristic = write
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                openResult?.let { if (!it.isCompleted) it.complete(false) }
                return
            }
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                client.writeDescriptor(cccd)
            }
            if (!started) openResult?.let { if (!it.isCompleted) it.complete(false) }
        }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            connected = status == BluetoothGatt.GATT_SUCCESS
            openResult?.let { if (!it.isCompleted) it.complete(connected) }
            if (connected) client.requestMtu(247)
        }
        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payloadSize = (mtu - 3).coerceIn(20, 244)
        }
        @Deprecated("Android 12 and earlier")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            incoming.accept(characteristic.value ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.accept(value)
        }
        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeSucceeded = status == BluetoothGatt.GATT_SUCCESS
            if (!writeSucceeded) connected = false
            writeLatch?.countDown()
        }
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (connected) return@withContext true
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = manager.adapter?.getRemoteDevice(address) ?: return@withContext false
            val pending = CompletableDeferred<Boolean>()
            openResult = pending
            gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            val ok = withTimeoutOrNull(15000) { pending.await() } == true
            openResult = null
            if (!ok) close()
            ok
        } catch (_: Exception) {
            close()
            false
        }
    }
    override fun close() {
        connected = false
        openResult?.let { if (!it.isCompleted) it.complete(false) }
        writeLatch?.countDown()
        incoming.close()
        writeCharacteristic = null
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
    private companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val HM10_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val HM10_DATA: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
