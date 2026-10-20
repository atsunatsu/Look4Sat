/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.framework

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface SppSocket {
    val isConnected: Boolean
    val input: InputStream
    val output: OutputStream
    fun connect()
    fun close()
}

internal interface SppConnector {
    fun prepare()
    fun create(secure: Boolean): SppSocket
}

class BluetoothSppRadioTransport internal constructor(
    private val connector: SppConnector,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : RadioTransport {
    constructor(
        manager: BluetoothManager?, address: String,
        diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
        serviceUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    ) : this(AndroidSppConnector(manager, address, serviceUuid), diagnostics)

    private var socket: SppSocket? = null
    private var nextMode = 0
    private var secure = false
    private var secureFirst = false
    private var connectTimeoutMillis = 4_000L
    override var lastIoFailure: String? = null
        private set
    override val isConnected get() = socket?.isConnected == true
    override val connectionDescription get() = "bluetooth/${if (nextMode == 0) "unopened" else if (secure) "secure" else "insecure"}"
    override val canTryAlternativeConnection get() = nextMode < 2
    override fun configureBluetoothConnection(secureFirst: Boolean, timeoutMillis: Long) {
        require(timeoutMillis > 0L)
        this.secureFirst = secureFirst
        connectTimeoutMillis = timeoutMillis
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        closeSocket("new connection")
        nextMode = 0
        lastIoFailure = null
        try {
            connector.prepare()
        } catch (error: Exception) {
            failure("prepare-failed", error)
            nextMode = 2
            return@withContext false
        }
        connectNext()
    }

    override suspend fun connectAlternative(): Boolean = withContext(Dispatchers.IO) {
        if (!canTryAlternativeConnection) return@withContext false
        closeSocket("Protocol handshake failed; trying alternative RFCOMM")
        connectNext()
    }

    private suspend fun connectNext(): Boolean {
        while (nextMode < 2) {
            secure = nextMode++ == if (secureFirst) 0 else 1
            record("connect-attempt", "mode=$connectionDescription")
            try {
                val candidate = connector.create(secure)
                socket = candidate
                val connected = socketOperation(candidate, connectTimeoutMillis) {
                    candidate.connect()
                    candidate.isConnected
                }
                if (connected == true) {
                    lastIoFailure = null
                    record("connected", "mode=$connectionDescription RFCOMM established; protocol handshake pending")
                    return true
                }
                if (connected == null) {
                    failure("connect-timeout", IOException("RFCOMM connection not established within $connectTimeoutMillis ms"))
                } else {
                    failure("connect-failed", IOException("RFCOMM returned without a connected socket"))
                }
            } catch (cancelled: CancellationException) {
                closeSocket("connection cancelled by app")
                throw cancelled
            } catch (error: Exception) {
                failure("connect-failed", error)
            }
            closeSocket("RFCOMM attempt failed")
        }
        return false
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeSocket("disconnect requested by caller")
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext false.also {
            failure("write-failed", IOException("No RFCOMM socket"))
        }
        try {
            val written = socketOperation(current, 1_000L) {
                current.output.apply { write(bytes); flush() }
                true
            } == true
            if (!written) {
                failure("write-timeout", IOException("Bluetooth write exceeded 1000 ms"))
                closeSocket("write timeout")
            }
            written
        } catch (cancelled: CancellationException) {
            closeSocket("write cancelled by app")
            throw cancelled
        } catch (error: Exception) {
            failure("write-failed", error)
            closeSocket("write failure")
            false
        }
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext ByteArray(0)
        if (maxBytes <= 0) return@withContext ByteArray(0)
        try {
            val result = socketOperation(current, 1_000L) {
                val input = current.input
                val count = minOf(input.available(), maxBytes)
                if (count <= 0) ByteArray(0) else {
                    val buffer = ByteArray(count)
                    val read = input.read(buffer)
                    if (read < 0) throw IOException("Bluetooth stream ended")
                    buffer.copyOf(read)
                }
            }
            if (result == null) {
                failure("read-timeout", IOException("Bluetooth read exceeded 1000 ms"))
                closeSocket("read timeout")
            }
            result ?: ByteArray(0)
        } catch (cancelled: CancellationException) {
            closeSocket("read cancelled by app")
            throw cancelled
        } catch (error: Exception) {
            failure("read-failed", error)
            closeSocket("read failure")
            ByteArray(0)
        }
    }

    // BluetoothSocket ignores thread interruption on some stacks. Cancellation must close it.
    private suspend fun <T> socketOperation(current: SppSocket, timeout: Long, operation: () -> T): T? =
        withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { current.close() } }
                CoroutineScope(Dispatchers.IO).launch {
                    if (continuation.isActive) {
                        continuation.resumeWith(runCatching(operation))
                    }
                }
            }
        }

    private fun closeSocket(reason: String) {
        val current = socket ?: return
        socket = null
        record("close", "origin=app mode=$connectionDescription reason=$reason")
        runCatching { current.close() }
    }

    private fun failure(stage: String, error: Exception) {
        lastIoFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        record(stage, "mode=$connectionDescription $lastIoFailure", ControlDiagnosticSeverity.ERROR)
    }

    private fun record(stage: String, message: String, severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "bluetooth/$stage", message, severity)
    }
}

@SuppressLint("MissingPermission")
private class AndroidSppConnector(private val manager: BluetoothManager?, private val address: String, private val uuid: UUID) : SppConnector {
    override fun prepare() {
        val adapter = manager?.adapter ?: throw IOException("Bluetooth adapter unavailable")
        check(adapter.isEnabled) { "Bluetooth is disabled" }
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) { "Invalid Bluetooth address" }
        // Discovery is optional; permission to connect does not imply permission to cancel discovery.
        runCatching { adapter.cancelDiscovery() }
    }

    override fun create(secure: Boolean): SppSocket {
        val device = checkNotNull(manager?.adapter).getRemoteDevice(address)
        val socket = if (secure) device.createRfcommSocketToServiceRecord(uuid)
            else device.createInsecureRfcommSocketToServiceRecord(uuid)
        return object : SppSocket {
            override val isConnected get() = socket.isConnected
            override val input get() = socket.inputStream
            override val output get() = socket.outputStream
            override fun connect() = socket.connect()
            override fun close() = socket.close()
        }
    }
}
