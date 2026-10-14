/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** One reader and one in-flight transaction. Transport establishment is not CI-V readiness. */
internal class Ic705CivSession(
    private val transport: RadioTransport,
    private val address: Byte,
    private val diagnostics: IControlDiagnostics,
    private val disconnected: () -> Unit,
    private val heartbeatMillis: Long = 5_000L,
    private val heartbeatTimeoutMillis: Long = 2_000L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    @Volatile private var pending: Pending? = null
    @Volatile private var running = true
    private var resyncRequired = false
    @Volatile private var lastRequestNanos = System.nanoTime()
    private var input = ByteArray(0)
    private val reader = scope.launch {
        try {
            while (isActive && running) {
                val chunk = transport.readAvailable(1024)
                if (chunk.isEmpty()) {
                    if (!transport.isConnected) {
                        failConnection("Transport closed; ${transport.lastIoFailure ?: "no I/O detail"}")
                        break
                    }
                    delay(10L)
                } else {
                    pending?.let { it.received = (it.received + chunk).takeLast(96).toByteArray() }
                    input += chunk
                    consumeFrames()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failConnection("${error.javaClass.simpleName}: ${error.message}")
        } finally {
            pending?.reply?.complete(null)
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
            scope.cancel()
        }
    }
    private val heartbeat = scope.launch {
        var failures = 0
        while (isActive && running) {
            delay(heartbeatMillis)
            if (pending != null || System.nanoTime() - lastRequestNanos < heartbeatMillis * 1_000_000L) continue
            val command = IcomCivProtocol.buildReadFreqCommand().also { it[2] = address }
            val response = request(command, IcomCivProtocol.CMD_READ_FREQ, heartbeatTimeoutMillis)
            val valid = response?.takeIf { it.cmd == IcomCivProtocol.CMD_READ_FREQ }
                ?.payload?.let(IcomCivProtocol::parseFrequencyPayload)?.let { it > 0L } == true
            failures = if (valid) 0 else failures + 1
            if (failures >= 3) {
                failConnection("Three consecutive idle CI-V heartbeat failures")
                break
            }
        }
    }

    suspend fun request(
        command: ByteArray,
        expected: Byte,
        timeoutMillis: Long = 2_000L,
        trace: Boolean = false,
        payloadMatches: (ByteArray) -> Boolean = { true }
    ): IcomCivProtocol.ParsedResponse? = commandMutex.withLock {
        // A timed-out setter can leave a late, unnumbered ACK. Re-establish a read barrier
        // before another setter; never replay a PTT or other side-effecting command.
        val stoppingPtt = command.getOrNull(4) == IcomCivProtocol.CMD_TRANSCEIVER_STATUS &&
            command.getOrNull(5) == IcomCivProtocol.SUB_PTT && command.getOrNull(6) == 0.toByte()
        if (resyncRequired && expected == IcomCivProtocol.ACK_OK && !stoppingPtt) {
            delay(200L)
            val probe = IcomCivProtocol.buildReadFreqCommand().also { it[2] = address }
            val synced = exchange(probe, IcomCivProtocol.CMD_READ_FREQ, timeoutMillis, trace) {
                IcomCivProtocol.parseFrequencyPayload(it) != null
            }
            if (synced?.cmd != IcomCivProtocol.CMD_READ_FREQ) return@withLock null
        }
        exchange(command, expected, timeoutMillis, trace, payloadMatches)
    }

    private suspend fun exchange(
        command: ByteArray,
        expected: Byte,
        timeoutMillis: Long,
        trace: Boolean,
        payloadMatches: (ByteArray) -> Boolean
    ): IcomCivProtocol.ParsedResponse? {
        if (!running || !transport.isConnected) return null
        lastRequestNanos = System.nanoTime()
        val transaction = Pending(expected, payloadMatches)
        pending = transaction
        try {
            if (trace) record("write", "command=${IcomCivProtocol.toHex(command)} mode=${transport.connectionDescription}")
            if (!transport.write(command)) {
                record("write/failed", "command=${IcomCivProtocol.toHex(command)} io=${transport.lastIoFailure ?: "write returned false"}", ControlDiagnosticSeverity.ERROR)
                failConnection("CI-V write failed")
                return null
            }
            val response = withTimeoutOrNull(timeoutMillis) { transaction.reply.await() }
            when {
                response == null -> {
                    resyncRequired = true
                    record(if (running) "reply/timeout" else "reply/disconnected",
                        "command=${IcomCivProtocol.toHex(command)} received=${IcomCivProtocol.toHex(transaction.received)} io=${transport.lastIoFailure ?: "none"}",
                        ControlDiagnosticSeverity.ERROR)
                }
                response.cmd == IcomCivProtocol.ACK_NG -> record("reply/rejected",
                    "command=${IcomCivProtocol.toHex(command)} NG=FA", ControlDiagnosticSeverity.ERROR)
                else -> {
                    resyncRequired = false
                    if (trace) record("reply/received", "command=${IcomCivProtocol.toHex(command)} received=${IcomCivProtocol.toHex(transaction.received)}")
                }
            }
            return response
        } catch (cancelled: CancellationException) {
            resyncRequired = true
            throw cancelled
        } catch (error: Exception) {
            record("write/failed", "${error.javaClass.simpleName}: ${error.message}", ControlDiagnosticSeverity.ERROR)
            failConnection("CI-V transaction I/O failed")
            return null
        } finally {
            if (pending === transaction) pending = null
        }
    }

    private fun consumeFrames() {
        var offset = 0
        while (offset + 1 < input.size) {
            if (input[offset] != IcomCivProtocol.PREAMBLE || input[offset + 1] != IcomCivProtocol.PREAMBLE) {
                offset++
                continue
            }
            val end = (offset + 2 until input.size).firstOrNull { input[it] == IcomCivProtocol.END_OF_MSG } ?: break
            val frame = input.copyOfRange(offset, end + 1)
            offset = end + 1
            val response = IcomCivProtocol.parseResponse(frame, null, address) ?: continue
            val waiting = pending ?: continue
            if (waiting.reply.isCompleted) continue
            if (response.cmd == IcomCivProtocol.ACK_NG ||
                response.cmd == waiting.expected && waiting.payloadMatches(response.payload)) {
                waiting.reply.complete(response)
            }
        }
        if (offset > 0) input = input.copyOfRange(offset, input.size)
        if (input.size > 8192) {
            input = input.takeLast(1).toByteArray()
            record("read/overflow", "Discarded malformed oversized frame", ControlDiagnosticSeverity.WARNING)
        }
    }

    private fun failConnection(reason: String) {
        if (!running) return
        running = false
        record("read/disconnected", reason, ControlDiagnosticSeverity.ERROR)
        pending?.reply?.complete(null)
        disconnected()
    }

    suspend fun close() {
        running = false
        pending?.reply?.complete(null)
        heartbeat.cancel()
        reader.cancel()
        reader.join()
        scope.cancel()
    }

    private fun record(stage: String, message: String, severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "civ/$stage", message, severity)
    }

    private class Pending(val expected: Byte, val payloadMatches: (ByteArray) -> Boolean) {
        val reply = CompletableDeferred<IcomCivProtocol.ParsedResponse?>()
        @Volatile var received = ByteArray(0)
    }
}
