/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.model.RadioTcpEndpoint
import com.rtbishop.look4sat.core.domain.model.parseRadioTcpEndpoint
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Backward-compatible CAT specialization of the shared station-control transport. */
interface RadioTransport : ControlTransport {
    val lastIoFailure: String? get() = null
    val connectionDescription: String get() = "transport"
    val canTryAlternativeConnection: Boolean get() = false
    suspend fun connectAlternative(): Boolean = false
    fun configureBluetoothConnection(secureFirst: Boolean, timeoutMillis: Long) = Unit
    /** Native CDC devices may gate replies on DTR. Generic CAT adapters stay deasserted. */
    fun setCdcDtrOnConnect(enabled: Boolean) = Unit
}

class TcpRadioTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int = 1_500
) : RadioTransport {
    private var socket: Socket? = null
    override val connectionDescription get() = "tcp"
    override val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65_535 || timeoutMillis <= 0) return@withContext false
        runCatching { socket?.close() }
        val candidate = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
        }
        socket = candidate
        try {
            val connected = withTimeoutOrNull(timeoutMillis.toLong() + CONNECT_TIMEOUT_MARGIN_MILLIS) {
                runInterruptible { candidate.connect(InetSocketAddress(host, port), timeoutMillis) }
                true
            } == true
            if (!connected) {
                runCatching { candidate.close() }
                socket = null
            }
            connected
        } catch (cancelled: CancellationException) {
            runCatching { candidate.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { candidate.close() }
            socket = null
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext false
        try {
            val written = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible { current.getOutputStream().apply { write(bytes); flush() } }
                true
            } == true
            if (!written) {
                runCatching { current.close() }
                socket = null
            }
            written
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { current.close() }
            socket = null
            false
        }
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext ByteArray(0)
        try {
            val input = current.getInputStream()
            val count = minOf(input.available(), maxBytes)
            if (count <= 0) return@withContext ByteArray(0)
            val result = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible {
                    val buffer = ByteArray(count)
                    val read = input.read(buffer)
                    if (read > 0) buffer.copyOf(read) else ByteArray(0)
                }
            }
            if (result == null) {
                runCatching { current.close() }
                socket = null
            }
            result ?: ByteArray(0)
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { current.close() }
            socket = null
            ByteArray(0)
        }
    }

    private companion object {
        const val IO_TIMEOUT_MILLIS = 1_000L
        const val CONNECT_TIMEOUT_MARGIN_MILLIS = 250L
    }
}

/** USB CAT transport for CDC-ACM, CP210x, FTDI, and CH34x adapters. */
class UsbSerialRadioTransport(
    context: Context,
    deviceSelector: String,
    private val baudRate: Int,
    private val stopBits: Int = 1,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : RadioTransport {
    private var cdcDtrOnConnect = false
    @Volatile override var lastIoFailure: String? = null
        private set
    private var connectedDevice: UsbDevice? = null
    override fun setCdcDtrOnConnect(enabled: Boolean) { cdcDtrOnConnect = enabled }
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)
    private val selector = parseUsbSerialSelector(deviceSelector)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces: List<UsbInterface> = emptyList()
    private var selectedPort: UsbSerialPortCandidate? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var pendingRead = ByteArray(0)
    private val lineConfiguration = usbSerialLineConfiguration(stopBits)
    override val connectionDescription get() = "usb driver=${selectedPort?.driver ?: "unopened"} " +
        "control=${selector?.controlInterfaceId} data=${selector?.dataInterfaceId} baud=$baudRate stopBits=$stopBits"
    override val isConnected: Boolean
        get() = connection != null && claimedInterfaces.isNotEmpty() && input != null && output != null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        pendingRead = ByteArray(0)
        lastIoFailure = null
        val parsedSelector = selector ?: return@withContext connectionFailed("Invalid USB port selector")
        val device = resolveUsbDevice(manager.deviceList.values, parsedSelector)
            ?: return@withContext connectionFailed("Selected USB device is not attached or identity changed")
        val serialLine = lineConfiguration ?: return@withContext connectionFailed("Unsupported stop-bit setting")
        if (!manager.hasPermission(device)) return@withContext connectionFailed("USB permission not granted")
        if (baudRate <= 0) return@withContext connectionFailed("Invalid USB baud rate")
        val opened = manager.openDevice(device) ?: return@withContext connectionFailed("Android could not open USB device")
        val claimed = mutableListOf<UsbInterface>()
        try {
            val descriptors = opened.rawDescriptors ?: ByteArray(0)
            val ports = findUsbSerialPorts(device, parseCdcInterfaceAssociations(descriptors))
            val port = ports.firstOrNull {
                it.control.id == parsedSelector.controlInterfaceId &&
                    it.data.id == parsedSelector.dataInterfaceId
            }
            if (port == null) {
                opened.close()
                return@withContext connectionFailed("Selected USB serial interface not found")
            }
            val interfaces = if (port.driver == UsbSerialDriverKind.CH34X) {
                // Composite CH34x devices can expose a USB-audio function next to CAT.
                // Leave audio interfaces available to the operating system.
                (0 until device.interfaceCount).map(device::getInterface).filterNot {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO
                }
            } else {
                listOf(port.control, port.data).distinctBy { it.id }
            }
            for (intf in interfaces) {
                if (!opened.claimInterface(intf, true)) {
                    claimed.asReversed().forEach { opened.releaseInterface(it) }
                    opened.close()
                    return@withContext connectionFailed("Could not claim USB interface ${intf.id}")
                }
                claimed += intf
            }
            val selectedInput = (0 until port.data.endpointCount).map(port.data::getEndpoint)
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            val selectedOutput = (0 until port.data.endpointCount).map(port.data::getEndpoint)
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            val configured = selectedInput != null && selectedOutput != null && when (port.driver) {
                UsbSerialDriverKind.CDC_ACM -> configureCdc(opened, port.control, serialLine)
                UsbSerialDriverKind.CP210X -> configureCp210x(opened, device, port, serialLine)
                UsbSerialDriverKind.FTDI -> configureFtdi(opened, device, port, serialLine)
                UsbSerialDriverKind.CH34X -> configureCh34x(opened, baudRate, serialLine)
            }
            if (!configured) {
                claimed.asReversed().forEach { opened.releaseInterface(it) }
                opened.close()
                return@withContext connectionFailed("Serial configuration failed driver=${port.driver} portIndex=${port.portIndex}; check baud/line settings")
            }
            claimedInterfaces = claimed
            selectedPort = port
            input = selectedInput
            output = selectedOutput
            connection = opened
            connectedDevice = device
            diagnostics.record(ControlDiagnosticSource.RADIO, "usb/connected",
                "$connectionDescription portIndex=${port.portIndex} VID=%04X PID=%04X IN=${selectedInput.address} OUT=${selectedOutput.address}".format(device.vendorId, device.productId))
            true
        } catch (cancelled: CancellationException) {
            claimed.asReversed().forEach { runCatching { opened.releaseInterface(it) } }
            runCatching { opened.close() }
            throw cancelled
        } catch (error: Exception) {
            claimed.asReversed().forEach { runCatching { opened.releaseInterface(it) } }
            runCatching { opened.close() }
            connectionFailed("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private fun connectionFailed(reason: String): Boolean {
        lastIoFailure = reason
        diagnostics.record(ControlDiagnosticSource.RADIO, "usb/connect-failed", "$connectionDescription reason=$reason", ControlDiagnosticSeverity.ERROR)
        return false
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val opened = connection
        val port = selectedPort
        if (opened != null) diagnostics.record(ControlDiagnosticSource.RADIO, "usb/close", "origin=app $connectionDescription disconnect requested by caller")
        if (opened != null && port != null) runCatching {
            when (port.driver) {
                UsbSerialDriverKind.CDC_ACM -> opened.controlTransfer(
                    CDC_REQUEST_TYPE,
                    CDC_SET_CONTROL_LINE_STATE,
                    0,
                    port.control.id,
                    null,
                    0,
                    IO_TIMEOUT_MILLIS
                )
                UsbSerialDriverKind.CP210X -> opened.controlTransfer(
                    USB_VENDOR_OUT_INTERFACE,
                    CP210X_IFC_ENABLE,
                    0,
                    port.portIndex,
                    null,
                    0,
                    IO_TIMEOUT_MILLIS
                )
                else -> Unit
            }
        }
        claimedInterfaces.asReversed().forEach { intf ->
            runCatching { opened?.releaseInterface(intf) }
        }
        opened?.close()
        connection = null
        connectedDevice = null
        claimedInterfaces = emptyList()
        selectedPort = null
        input = null
        output = null
        pendingRead = ByteArray(0)
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext true
        val current = connection ?: run { lastIoFailure="USB connection closed";return@withContext false }
        val endpoint = output ?: run { lastIoFailure="USB OUT endpoint unavailable";return@withContext false }
        lastIoFailure=null
        val timeout=usbRadioWriteTimeoutMillis(cdcDtrOnConnect,selectedPort?.driver)
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(bytes.size - offset, MAX_USB_TRANSFER_BYTES)
            val written = try { current.bulkTransfer(endpoint, bytes, offset, count, timeout) }
            catch(cancelled:CancellationException) {throw cancelled}
            catch(error:Exception) { lastIoFailure="bulk OUT exception=${error.javaClass.simpleName} sent=$offset/${bytes.size}";return@withContext false }
            if (written <= 0) {
                val device=connectedDevice
                val attached=runCatching { device!=null && manager.deviceList.values.any { it.deviceId==device.deviceId } }.getOrNull()
                lastIoFailure="bulk OUT result=$written sent=$offset/${bytes.size} timeoutMs=$timeout attached=$attached driver=${selectedPort?.driver}"
                return@withContext false
            }
            offset += written
        }
        true
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        if (maxBytes <= 0) return@withContext ByteArray(0)
        if (pendingRead.isNotEmpty()) {
            val (result, remaining) = splitReadChunk(pendingRead, maxBytes)
            pendingRead = remaining
            return@withContext result
        }
        val current = connection ?: return@withContext ByteArray(0)
        val endpoint = input ?: return@withContext ByteArray(0)
        val port = selectedPort ?: return@withContext ByteArray(0)
        val buffer = if (port.driver == UsbSerialDriverKind.FTDI) {
            val size = ftdiReadBufferSize(maxBytes, endpoint.maxPacketSize)
            if (size <= 0) return@withContext ByteArray(0)
            ByteArray(size)
        } else {
            // Read at least one complete USB packet, then retain any bytes the
            // protocol caller did not request. Tiny one-byte ACK reads must not
            // truncate a packet that also contains subsequent response bytes.
            ByteArray(
                maxOf(maxBytes, endpoint.maxPacketSize.coerceAtLeast(1))
                    .coerceAtMost(MAX_USB_TRANSFER_BYTES)
            )
        }
        val count = try {
            current.bulkTransfer(endpoint, buffer, buffer.size, READ_POLL_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastIoFailure = "bulk IN ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            diagnostics.record(ControlDiagnosticSource.RADIO, "usb/read-failed", "$connectionDescription $lastIoFailure", ControlDiagnosticSeverity.ERROR)
            throw error
        }
        if (count <= 0) return@withContext ByteArray(0)
        val payload = if (port.driver == UsbSerialDriverKind.FTDI) {
            filterFtdiStatusBytes(buffer, count, endpoint.maxPacketSize)
        } else {
            buffer.copyOf(count)
        }
        val (result, remaining) = splitReadChunk(payload, maxBytes)
        pendingRead = remaining
        result
    }

    private fun configureCdc(
        connection: UsbDeviceConnection,
        intf: UsbInterface,
        serialLine: UsbSerialLineConfiguration
    ): Boolean {
        val lineCoding = byteArrayOf(
            baudRate.toByte(), (baudRate shr 8).toByte(), (baudRate shr 16).toByte(), (baudRate shr 24).toByte(),
            serialLine.cdcStopBits, 0, 8
        )
        val lineCodingBytes = connection.controlTransfer(
            CDC_REQUEST_TYPE,
            CDC_SET_LINE_CODING,
            0,
            intf.id,
            lineCoding,
            lineCoding.size,
            IO_TIMEOUT_MILLIS
        )
        if (lineCodingBytes != lineCoding.size) return false
        return connection.controlTransfer(
            CDC_REQUEST_TYPE,
            CDC_SET_CONTROL_LINE_STATE,
            cdcControlLineState(cdcDtrOnConnect),
            intf.id,
            null,
            0,
            IO_TIMEOUT_MILLIS
        ) == 0
    }

    private fun configureCp210x(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        port: UsbSerialPortCandidate,
        serialLine: UsbSerialLineConfiguration
    ): Boolean {
        if (!isCp210xLineConfigurationSupported(device.interfaceCount, port.portIndex, serialLine)) return false
        val index = port.portIndex
        if (cp210xOut(connection, CP210X_IFC_ENABLE, 1, index) != 0) return false
        if (cp210xOut(connection, CP210X_SET_MHS, CP210X_DTR_RTS_DISABLED, index) != 0) return false
        val noFlowControl = ByteArray(CP210X_FLOW_CONTROL_BYTES)
        if (connection.controlTransfer(
                USB_VENDOR_OUT_INTERFACE,
                CP210X_SET_FLOW,
                0,
                index,
                noFlowControl,
                noFlowControl.size,
                IO_TIMEOUT_MILLIS
            ) != noFlowControl.size
        ) return false
        val rate = byteArrayOf(
            baudRate.toByte(),
            (baudRate shr 8).toByte(),
            (baudRate shr 16).toByte(),
            (baudRate shr 24).toByte()
        )
        if (connection.controlTransfer(
                USB_VENDOR_OUT_INTERFACE,
                CP210X_SET_BAUD_RATE,
                0,
                index,
                rate,
                rate.size,
                IO_TIMEOUT_MILLIS
            ) < 0
        ) return false
        return cp210xOut(connection, CP210X_SET_LINE_CONTROL, serialLine.cp210xLineControl, index) == 0
    }

    private fun configureFtdi(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        port: UsbSerialPortCandidate,
        serialLine: UsbSerialLineConfiguration
    ): Boolean {
        val portNumber = port.portIndex + 1
        if (vendorOut(connection, FTDI_RESET, 0, portNumber) != 0) return false
        if (vendorOut(connection, FTDI_MODEM_CONTROL, FTDI_DTR_RTS_DISABLED, portNumber) != 0) return false
        if (vendorOut(connection, FTDI_FLOW_CONTROL, 0, portNumber) != 0) return false
        val descriptors = connection.rawDescriptors ?: return false
        val deviceType = descriptors.getOrNull(13)?.toInt()?.and(0xFF)
        val baudWithPort = device.interfaceCount > 1 || deviceType in setOf(7, 8, 9)
        val divisor = calculateFtdiBaudDivisor(baudRate, port.portIndex, baudWithPort) ?: return false
        if (vendorOut(connection, FTDI_SET_BAUD_RATE, divisor.first, divisor.second) != 0) return false
        return vendorOut(connection, FTDI_SET_DATA, serialLine.ftdiSetData, portNumber) == 0
    }

    private fun configureCh34x(
        connection: UsbDeviceConnection,
        requestedBaudRate: Int,
        serialLine: UsbSerialLineConfiguration
    ): Boolean {
        val initial = ByteArray(2)
        if (vendorIn(connection, CH34X_READ_VERSION, 0, 0, initial) != initial.size) return false
        if (vendorOut(connection, CH34X_SERIAL_INIT, 0, 0) < 0) return false
        if (!setCh34xBaudRate(connection, CH34X_DEFAULT_BAUD_RATE)) return false
        val register = ByteArray(2)
        if (vendorIn(connection, CH34X_READ_REG, 0x2518, 0, register) != register.size) return false
        if (vendorOut(connection, CH34X_WRITE_REG, 0x2518, serialLine.ch34xLineControl) < 0) return false
        if (vendorIn(connection, CH34X_READ_REG, 0x0706, 0, register) != register.size) return false
        if (vendorOut(connection, CH34X_SERIAL_INIT, 0x501F, 0xD90A) < 0) return false
        if (!setCh34xBaudRate(connection, requestedBaudRate)) return false
        if (vendorOut(connection, CH34X_MODEM_CTRL, CH34X_DTR_RTS_DISABLED, 0) < 0) return false
        return vendorOut(connection, CH34X_WRITE_REG, 0x2518, serialLine.ch34xLineControl) >= 0
    }

    private fun setCh34xBaudRate(connection: UsbDeviceConnection, rate: Int): Boolean {
        val registers = calculateCh34xBaudRegisters(rate) ?: return false
        return vendorOut(connection, CH34X_WRITE_REG, 0x1312, registers.first) >= 0 &&
            vendorOut(connection, CH34X_WRITE_REG, 0x0F2C, registers.second) >= 0
    }

    private fun vendorOut(connection: UsbDeviceConnection, request: Int, value: Int, index: Int): Int =
        connection.controlTransfer(
            USB_VENDOR_OUT_DEVICE,
            request,
            value,
            index,
            null,
            0,
            IO_TIMEOUT_MILLIS
        )

    private fun cp210xOut(connection: UsbDeviceConnection, request: Int, value: Int, index: Int): Int =
        connection.controlTransfer(
            USB_VENDOR_OUT_INTERFACE,
            request,
            value,
            index,
            null,
            0,
            IO_TIMEOUT_MILLIS
        )

    private fun vendorIn(
        connection: UsbDeviceConnection,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray
    ): Int = connection.controlTransfer(
        USB_VENDOR_IN_DEVICE,
        request,
        value,
        index,
        buffer,
        buffer.size,
        IO_TIMEOUT_MILLIS
    )

    private companion object {
        const val IO_TIMEOUT_MILLIS = 1_000
        const val READ_POLL_MILLIS = 20
        const val CDC_REQUEST_TYPE = 0x21
        const val CDC_SET_LINE_CODING = 0x20
        const val CDC_SET_CONTROL_LINE_STATE = 0x22
        const val USB_VENDOR_OUT_DEVICE = 0x40
        const val USB_VENDOR_IN_DEVICE = 0xC0
        const val USB_VENDOR_OUT_INTERFACE = 0x41
        const val CP210X_IFC_ENABLE = 0x00
        const val CP210X_SET_LINE_CONTROL = 0x03
        const val CP210X_SET_MHS = 0x07
        const val CP210X_SET_FLOW = 0x13
        const val CP210X_SET_BAUD_RATE = 0x1E
        const val CP210X_DTR_RTS_DISABLED = 0x0300
        const val CP210X_FLOW_CONTROL_BYTES = 16
        const val FTDI_RESET = 0
        const val FTDI_MODEM_CONTROL = 1
        const val FTDI_FLOW_CONTROL = 2
        const val FTDI_SET_BAUD_RATE = 3
        const val FTDI_SET_DATA = 4
        const val FTDI_DTR_RTS_DISABLED = 0x0300
        const val CH34X_READ_VERSION = 0x5F
        const val CH34X_SERIAL_INIT = 0xA1
        const val CH34X_READ_REG = 0x95
        const val CH34X_WRITE_REG = 0x9A
        const val CH34X_MODEM_CTRL = 0xA4
        const val CH34X_DTR_RTS_DISABLED = 0xFFFF
        const val CH34X_DEFAULT_BAUD_RATE = 9_600
    }
}

// Bit 0 is DTR; bit 1 (RTS) stays clear to avoid keying modem-line PTT adapters.
internal fun cdcControlLineState(dtr: Boolean): Int = if (dtr) 1 else 0
internal fun usbRadioWriteTimeoutMillis(nativeCdc: Boolean, driver: UsbSerialDriverKind?): Int =
    if (nativeCdc && driver==UsbSerialDriverKind.CDC_ACM) 150 else 1000

internal data class UsbSerialSelector(
    val deviceId: Int,
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val vendorId: Int?,
    val productId: Int?
)

internal enum class UsbSerialDriverKind(val displayName: String) {
    CDC_ACM("CDC-ACM"),
    CP210X("CP210x"),
    FTDI("FTDI"),
    CH34X("CH34x")
}

internal data class UsbSerialLineConfiguration(
    val cdcStopBits: Byte,
    val cp210xLineControl: Int,
    val ftdiSetData: Int,
    val ch34xLineControl: Int
)

internal fun usbSerialLineConfiguration(stopBits: Int): UsbSerialLineConfiguration? = when (stopBits) {
    1 -> UsbSerialLineConfiguration(
        cdcStopBits = 0,
        cp210xLineControl = 0x0800,
        ftdiSetData = 0x0008,
        ch34xLineControl = 0xC3
    )
    2 -> UsbSerialLineConfiguration(
        cdcStopBits = 2,
        cp210xLineControl = 0x0802,
        ftdiSetData = 0x1008,
        ch34xLineControl = 0xC7
    )
    else -> null
}

internal fun isCp210xLineConfigurationSupported(
    interfaceCount: Int,
    portIndex: Int,
    configuration: UsbSerialLineConfiguration
): Boolean {
    val restrictedCp2105Port = interfaceCount == 2 && portIndex == 1
    val usesTwoStopBits = configuration.cp210xLineControl and 0x000F == 2
    return !restrictedCp2105Port || !usesTwoStopBits
}

private data class UsbSerialPortCandidate(
    val driver: UsbSerialDriverKind,
    val control: UsbInterface,
    val data: UsbInterface,
    val portIndex: Int
)

internal fun parseUsbSerialSelector(value: String): UsbSerialSelector? {
    val parts = value.split(':')
    if (parts.size != 3 && parts.size != 5) return null
    val deviceId = parts[0].toIntOrNull() ?: return null
    val controlId = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val dataId = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val vendorId = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    val productId = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    if (parts.size == 5 && (vendorId == null || productId == null)) return null
    return UsbSerialSelector(deviceId, controlId, dataId, vendorId, productId)
}

private fun resolveUsbDevice(devices: Collection<UsbDevice>, selector: UsbSerialSelector): UsbDevice? {
    val matchesIdentity: (UsbDevice) -> Boolean = { device ->
        selector.vendorId == null ||
            device.vendorId == selector.vendorId && device.productId == selector.productId
    }
    devices.firstOrNull { it.deviceId == selector.deviceId && matchesIdentity(it) }?.let { return it }
    if (selector.vendorId == null || selector.productId == null) return null
    return devices.filter(matchesIdentity).singleOrNull()
}

internal fun usbSerialDriverForVendor(vendorId: Int): UsbSerialDriverKind? = when (vendorId) {
    USB_VENDOR_SILICON_LABS -> UsbSerialDriverKind.CP210X
    USB_VENDOR_FTDI -> UsbSerialDriverKind.FTDI
    USB_VENDOR_QINHENG, USB_VENDOR_CH34X_ALT -> UsbSerialDriverKind.CH34X
    else -> null
}

private fun findUsbSerialPorts(
    device: UsbDevice,
    associations: Map<Int, Set<Int>>
): List<UsbSerialPortCandidate> {
    val interfaces = (0 until device.interfaceCount).map(device::getInterface)
    val cdcPorts = findCdcAcmPairs(interfaces, associations).map { pair ->
        UsbSerialPortCandidate(
            driver = UsbSerialDriverKind.CDC_ACM,
            control = pair.control,
            data = pair.data,
            portIndex = interfaces.indexOf(pair.data)
        )
    }
    if (cdcPorts.isNotEmpty()) return cdcPorts
    val driver = usbSerialDriverForVendor(device.vendorId) ?: return emptyList()
    if (driver == UsbSerialDriverKind.CH34X) {
        val data = interfaces.lastOrNull(::hasBulkSerialEndpoints) ?: return emptyList()
        return listOf(UsbSerialPortCandidate(driver, data, data, portIndex = 0))
    }
    return interfaces.mapIndexedNotNull { index, intf ->
        intf.takeIf(::hasBulkSerialEndpoints)?.let {
            UsbSerialPortCandidate(driver, it, it, index)
        }
    }
}

private data class CdcAcmPair(val control: UsbInterface, val data: UsbInterface)

private fun findCdcAcmPairs(
    interfaces: List<UsbInterface>,
    associations: Map<Int, Set<Int>>
): List<CdcAcmPair> {
    val controls = interfaces.filter {
        it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == CDC_ACM_SUBCLASS
    }
    val dataInterfaces = interfaces.filter { intf ->
        intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && hasBulkSerialEndpoints(intf)
    }
    val explicit = controls.flatMap { control ->
        associations[control.id].orEmpty().mapNotNull { dataId ->
            dataInterfaces.firstOrNull { it.id == dataId }?.let { CdcAcmPair(control, it) }
        }
    }
    if (explicit.isNotEmpty()) return explicit.distinctBy { it.control.id to it.data.id }
    val adjacent = controls.mapNotNull { control ->
        dataInterfaces.firstOrNull { it.id == control.id + 1 }?.let { CdcAcmPair(control, it) }
    }
    if (adjacent.isNotEmpty()) return adjacent.distinctBy { it.control.id to it.data.id }
    return if (controls.size == 1 && dataInterfaces.size == 1) {
        listOf(CdcAcmPair(controls.single(), dataInterfaces.single()))
    } else {
        emptyList()
    }
}

private fun hasBulkSerialEndpoints(intf: UsbInterface): Boolean {
    val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
    return endpoints.any {
        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
    } && endpoints.any {
        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
    }
}

internal fun calculateFtdiBaudDivisor(
    baudRate: Int,
    portIndex: Int,
    baudRateWithPort: Boolean
): Pair<Int, Int>? {
    if (baudRate <= 0 || portIndex < 0 || baudRate > 3_500_000) return null
    val divisor: Int
    val subdivisor: Int
    val effectiveBaudRate: Int
    when {
        baudRate >= 2_500_000 -> {
            divisor = 0
            subdivisor = 0
            effectiveBaudRate = 3_000_000
        }
        baudRate >= 1_750_000 -> {
            divisor = 1
            subdivisor = 0
            effectiveBaudRate = 2_000_000
        }
        else -> {
            val combinedDivisor = ((48_000_000 / baudRate) + 1) shr 1
            subdivisor = combinedDivisor and 0x07
            divisor = combinedDivisor shr 3
            if (divisor > 0x3FFF) return null
            effectiveBaudRate = ((48_000_000 / ((divisor shl 3) + subdivisor)) + 1) shr 1
        }
    }
    if (kotlin.math.abs(1.0 - effectiveBaudRate.toDouble() / baudRate) >= 0.031) return null
    var value = divisor
    var index = 0
    when (subdivisor) {
        0 -> Unit
        4 -> value = value or 0x4000
        2 -> value = value or 0x8000
        1 -> value = value or 0xC000
        3 -> index = index or 1
        5 -> {
            value = value or 0x4000
            index = index or 1
        }
        6 -> {
            value = value or 0x8000
            index = index or 1
        }
        7 -> {
            value = value or 0xC000
            index = index or 1
        }
    }
    if (baudRateWithPort) index = (index shl 8) or (portIndex + 1)
    return value to index
}

internal fun calculateCh34xBaudRegisters(baudRate: Int): Pair<Int, Int>? {
    if (baudRate <= 0) return null
    var factor: Long
    val divisor: Long
    if (baudRate == 921_600) {
        divisor = 7
        factor = 0xF300
    } else {
        factor = 1_532_620_800L / baudRate
        var mutableDivisor = 3L
        while (factor > 0xFFF0 && mutableDivisor > 0) {
            factor = factor shr 3
            mutableDivisor--
        }
        if (factor > 0xFFF0) return null
        factor = 0x10000 - factor
        divisor = mutableDivisor
    }
    val value1 = ((factor and 0xFF00) or (divisor or 0x0080)).toInt()
    val value2 = (factor and 0xFF).toInt()
    return value1 to value2
}

internal fun filterFtdiStatusBytes(buffer: ByteArray, byteCount: Int, maxPacketSize: Int): ByteArray {
    if (byteCount <= 0 || maxPacketSize <= FTDI_PACKET_STATUS_BYTES) return ByteArray(0)
    val limit = minOf(byteCount, buffer.size)
    val filtered = ByteArray(limit)
    var source = 0
    var destination = 0
    while (source < limit) {
        val packetEnd = minOf(source + maxPacketSize, limit)
        val payloadStart = minOf(source + FTDI_PACKET_STATUS_BYTES, packetEnd)
        val payloadSize = packetEnd - payloadStart
        if (payloadSize > 0) {
            buffer.copyInto(filtered, destination, payloadStart, packetEnd)
            destination += payloadSize
        }
        source += maxPacketSize
    }
    return filtered.copyOf(destination)
}

internal fun ftdiReadBufferSize(maxBytes: Int, maxPacketSize: Int): Int {
    if (maxBytes <= 0 || maxPacketSize <= FTDI_PACKET_STATUS_BYTES ||
        maxPacketSize > MAX_USB_TRANSFER_BYTES
    ) return 0
    val payloadPerPacket = maxPacketSize - FTDI_PACKET_STATUS_BYTES
    val requestedPackets = (maxBytes.toLong() + payloadPerPacket - 1L) / payloadPerPacket
    val maximumPackets = MAX_USB_TRANSFER_BYTES / maxPacketSize
    return (minOf(requestedPackets, maximumPackets.toLong()).coerceAtLeast(1L) * maxPacketSize).toInt()
}

internal fun splitReadChunk(bytes: ByteArray, maxBytes: Int): Pair<ByteArray, ByteArray> {
    if (maxBytes <= 0 || bytes.isEmpty()) return ByteArray(0) to bytes
    val count = minOf(bytes.size, maxBytes)
    return bytes.copyOfRange(0, count) to bytes.copyOfRange(count, bytes.size)
}

internal fun parseCdcInterfaceAssociations(descriptors: ByteArray): Map<Int, Set<Int>> {
    val associations = mutableMapOf<Int, MutableSet<Int>>()
    var offset = 0
    while (offset + 1 < descriptors.size) {
        val length = descriptors[offset].toInt() and 0xff
        val type = descriptors[offset + 1].toInt() and 0xff
        if (length < 2 || offset + length > descriptors.size) break
        when {
            type == USB_DESCRIPTOR_TYPE_CS_INTERFACE && length >= 5 &&
                (descriptors[offset + 2].toInt() and 0xff) == CDC_UNION_SUBTYPE -> {
                val master = descriptors[offset + 3].toInt() and 0xff
                val slaves = associations.getOrPut(master) { mutableSetOf() }
                for (index in offset + 4 until offset + length) slaves += descriptors[index].toInt() and 0xff
            }
            type == USB_DESCRIPTOR_TYPE_IAD && length >= 8 &&
                (descriptors[offset + 4].toInt() and 0xff) == UsbConstants.USB_CLASS_COMM &&
                (descriptors[offset + 5].toInt() and 0xff) == CDC_ACM_SUBCLASS -> {
                val first = descriptors[offset + 2].toInt() and 0xff
                val count = descriptors[offset + 3].toInt() and 0xff
                val members = associations.getOrPut(first) { mutableSetOf() }
                for (id in first + 1 until first + count) members += id
            }
        }
        offset += length
    }
    return associations.mapValues { it.value.toSet() }
}

private const val CDC_ACM_SUBCLASS = 0x02
private const val USB_DESCRIPTOR_TYPE_IAD = 0x0b
private const val USB_DESCRIPTOR_TYPE_CS_INTERFACE = 0x24
private const val CDC_UNION_SUBTYPE = 0x06
private const val USB_VENDOR_FTDI = 0x0403
private const val USB_VENDOR_SILICON_LABS = 0x10C4
private const val USB_VENDOR_QINHENG = 0x1A86
private const val USB_VENDOR_CH34X_ALT = 0x4348
private const val FTDI_PACKET_STATUS_BYTES = 2
private const val MAX_USB_TRANSFER_BYTES = 16_384

internal fun parseTcpEndpoint(value: String): RadioTcpEndpoint? = parseRadioTcpEndpoint(value)

class AndroidRadioTransportFactory(
    private val context: Context,
    private val bluetoothManager: BluetoothManager?,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) {
    fun create(type: String, address: String, baudRate: Int, stopBits: Int): RadioTransport = when (type) {
        "USB" -> UsbSerialRadioTransport(context, address, baudRate, stopBits, diagnostics)
        "TCP" -> {
            val endpoint = parseTcpEndpoint(address)
            TcpRadioTransport(endpoint?.host.orEmpty(), endpoint?.port ?: 0)
        }
        else -> BluetoothSppRadioTransport(bluetoothManager, address, diagnostics)
    }
}
