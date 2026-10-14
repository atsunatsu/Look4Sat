/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Icom CI-V controller over Bluetooth, USB serial, or TCP, including legacy IC-820 commands.
 *
 * The IC-705 emits broadcast frames continuously (band scope, UTC, signal
 * level, …).  A reply to any command we send may therefore be buried in
 * that noise. All response reads drain up to the model-specific timeout and
 * scan the entire accumulated buffer for the frame we expect rather than assuming
 * the very next byte is the response.
 */
class Ic705Controller(
    bluetoothManager: BluetoothManager?,
    private val deviceAddress: String,
    private val civAddress: Byte = IcomCivProtocol.ADDR_IC705,
    private val transport: RadioTransport = BluetoothSppRadioTransport(
        requireNotNull(bluetoothManager),
        deviceAddress
    ),
    private val variant: IcomCivVariant = IcomCivVariant.IC705,
    dedicatedSatelliteMode: Boolean = variant != IcomCivVariant.IC705,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : IRadioController {

    private val tag = when (variant) {
        IcomCivVariant.IC705 -> "IC705"
        IcomCivVariant.IC9700 -> "IC9700"
        IcomCivVariant.IC910 -> "IC910"
        IcomCivVariant.IC820 -> "IC820"
    }
    private val usesDedicatedSatelliteMode = dedicatedSatelliteMode &&
        (variant == IcomCivVariant.IC9700 || variant == IcomCivVariant.IC910)
    private val usesManualVfoSelection = usesDedicatedSatelliteMode ||
        variant == IcomCivVariant.IC910 || variant == IcomCivVariant.IC820
    private val ioMutex = Mutex()
    private var civSession: Ic705CivSession? = null
    private var duplexModeEnabled = false

    /** Time budget (ms) to wait for a response amid broadcast noise. */
    private var handshaking = false
    private var responseRejected = false
    private val responseTimeoutMs get() = when {
        handshaking -> 2_000L
        variant == IcomCivVariant.IC910 || variant == IcomCivVariant.IC820 -> 1_000L
        else -> 500L
    }
    /** Polling interval while draining the input buffer. */
    private val POLL_INTERVAL_MS = 20L
    /** Small pause after writing a command before reading the response. */
    private val WRITE_SETTLE_MS = 50L

    @Volatile override var isConnected: Boolean = false
        private set
    override val commandTimeoutMillis get() = if (variant == IcomCivVariant.IC705) 15_000L else 3_000L

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) {
            diagnose("connect/invalid", "No radio endpoint configured", ControlDiagnosticSeverity.ERROR)
            return@withContext false
        }
        try {
            if (variant == IcomCivVariant.IC705) transport.configureBluetoothConnection(true, 10_000L)
            diagnose("connect/transport", "model=$tag opening ${transport.connectionDescription}")
            isConnected = transport.connect()
            if (!isConnected) {
                diagnose("connect/transport-failed", transport.lastIoFailure ?: "Transport could not connect", ControlDiagnosticSeverity.ERROR)
                return@withContext false
            }
            handshaking = variant == IcomCivVariant.IC705
            do {
                if (variant == IcomCivVariant.IC705) {
                    civSession = Ic705CivSession(transport, civAddress, diagnostics, disconnected = { isConnected = false })
                }
                diagnose("connect/probe", "model=$tag mode=${transport.connectionDescription} address=0x${"%02X".format(civAddress.toInt() and 0xFF)}")
                val ready = if (variant == IcomCivVariant.IC705) {
                    probeReadOnlyConnection()
                } else {
                    // Preserve legacy setup; IC-705 probing never changes radio state.
                    ioMutex.withLock { sendAndWaitAck(IcomCivProtocol.buildEnterVfoModeCommand()) } &&
                        readFrequencyAndMode() != null
                }
                if (ready && isConnected && transport.isConnected) {
                    diagnose("connect/ready", "model=$tag mode=${transport.connectionDescription} CI-V verified")
                    return@withContext true
                }
                isConnected = false
                civSession?.close()
                civSession = null
                if (variant != IcomCivVariant.IC705 || !transport.canTryAlternativeConnection) break
                diagnose("connect/fallback", "Read-only handshake failed; trying remaining RFCOMM mode", ControlDiagnosticSeverity.WARNING)
                isConnected = transport.connectAlternative()
            } while (isConnected)
            closeFailedConnection("CI-V handshake failed after bounded attempts")
            false
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { closeFailedConnection("connection cancelled by app") }
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            diagnose("connect/exception", "${e.javaClass.simpleName}: ${e.message}", ControlDiagnosticSeverity.ERROR)
            closeFailedConnection("connection exception")
            false
        } finally {
            handshaking = false
        }
    }

    private suspend fun probeReadOnlyConnection(): Boolean {
        delay(500L)
        repeat(3) { attempt ->
            diagnose("connect/probe-attempt", "attempt=${attempt + 1}/3 mode=${transport.connectionDescription} command=03 (read frequency)")
            val frequency = ioMutex.withLock { readFrequencyLocked() }
            if (frequency != null && frequency > 0L) {
                diagnose("connect/frequency", "frequencyHz=$frequency")
                return true
            }
            if (frequency != null) diagnose("reply/invalid", "Read frequency returned non-positive value", ControlDiagnosticSeverity.ERROR)
            if (responseRejected || !isConnected || !transport.isConnected) return false
            if (attempt < 2) delay(150L)
        }
        return false
    }

    private suspend fun closeFailedConnection(reason: String) {
        diagnose("disconnect", "origin=app reason=$reason", ControlDiagnosticSeverity.WARNING)
        civSession?.close()
        civSession = null
        runCatching { transport.disconnect() }
        isConnected = false
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        diagnose("disconnect", "origin=app reason=disconnect requested by caller")
        try {
            if (isConnected && variant != IcomCivVariant.IC820) {
                withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) { pttOff() }
            }
            if (isConnected && duplexModeEnabled) {
                withTimeoutOrNull(DUPLEX_OFF_TIMEOUT_MS) { setSplitMode(enabled = false) }
            }
            civSession?.close()
            civSession = null
            transport.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Disconnect error: ${e.message}")
            civSession?.close()
            civSession = null
            runCatching { transport.disconnect() }
        } finally {
            duplexModeEnabled = false
            isConnected = false
            Log.i(tag, "Disconnected from $deviceAddress")
        }
    }

    // ── IRadioController – standard operations ──────────────────────────────

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820 && ic820Band(frequencyHz) == null) return@withContext false
        Log.d(tag, "setFrequency: ${frequencyHz}Hz")
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildSetFreqCommand(frequencyHz)
            Log.d(tag, "CMD setFreq → ${commandHex(cmd)}")
            sendAndWaitAck(cmd) &&
                (variant != IcomCivVariant.IC820 || readFrequencyLocked() == frequencyHz)
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!isModeSupported(mode)) {
            Log.w(tag, "setMode: mode '$mode' is not supported by $variant")
            return@withContext false
        }
        if (variant == IcomCivVariant.IC705) return@withContext ioMutex.withLock {
            val command = IcomCivProtocol.buildSetVfoModeCommand(true, mode, completeFields = true) ?: return@withLock false
            sendAndWaitAck(command) && verifyIc705ModeLocked(true, mode, IcomCivProtocol.isDataMode(mode))
        }
        if (variant == IcomCivVariant.IC910) return@withContext ioMutex.withLock { setIc910ModeLocked(mode) }
        val cmd = buildModelModeCommand(mode) ?: run {
            Log.w(tag, "setMode: unknown mode '$mode'")
            return@withContext false
        }
        Log.d(tag, "setMode: $mode")
        Log.d(tag, "CMD setMode → ${commandHex(cmd)}")
        ioMutex.withLock {
            sendAndWaitAck(cmd) &&
                (variant != IcomCivVariant.IC820 || readModeLocked() == normalizedMode(mode))
        }
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean =
        withContext(Dispatchers.IO) {
            if (variant == IcomCivVariant.IC910 || variant == IcomCivVariant.IC820) return@withContext !enabled
            if (variant == IcomCivVariant.IC705) return@withContext ioMutex.withLock {
                setIc705DataModeLocked(true, enabled, baseMode)
            }
            val command = IcomCivProtocol.buildDataModeCommand(enabled)
            Log.d(tag, "CMD DATA ${if (enabled) "ON" else "OFF"} → ${commandHex(command)}")
            ioMutex.withLock { sendAndWaitAck(command) }
        }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean =
        withContext(Dispatchers.IO) {
            if (variant == IcomCivVariant.IC910 || variant == IcomCivVariant.IC820) return@withContext !enabled
            if (variant == IcomCivVariant.IC705) return@withContext ioMutex.withLock {
                setIc705DataModeLocked(false, enabled, baseMode)
            }
            ioMutex.withLock {
                var rxRestored = false
                val configured = try {
                    sendAndWaitAck(selectVfoCommand(vfoA = false)) &&
                        sendAndWaitAck(IcomCivProtocol.buildDataModeCommand(enabled))
                } finally {
                    rxRestored = restoreRxVfo()
                }
                configured && rxRestored
            }
        }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820) return@withContext !enabled
        Log.d(tag, "setCtcssMode: $enabled")
        val cmd = IcomCivProtocol.buildCtcssModeCommand(enabled)
        Log.d(tag, "CMD ctcssMode → ${commandHex(cmd)}")
        ioMutex.withLock {
            val acknowledged = sendAndWaitAck(cmd)
            acknowledged
        }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820) return@withContext false
        Log.d(tag, "setCtcssTone: ${toneHz}Hz")
        val cmd = IcomCivProtocol.buildSetCtcssToneCommand(toneHz)
        Log.d(tag, "CMD ctcssTone → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val frequencyCommand = IcomCivProtocol.buildReadFreqCommand()
            Log.d(tag, "CMD readFreq → ${commandHex(frequencyCommand)}")
            val frequencyPayload = sendAndReadResponse(
                frequencyCommand,
                IcomCivProtocol.CMD_READ_FREQ
            ) ?: return@withLock null
            val frequency = parseModelFrequency(frequencyPayload)
                ?: return@withLock null

            val modeCommand = IcomCivProtocol.buildReadModeCommand()
            Log.d(tag, "CMD readMode → ${commandHex(modeCommand)}")
            val modePayload = sendAndReadResponse(
                modeCommand,
                IcomCivProtocol.CMD_READ_MODE
            ) ?: return@withLock null
            val mode = parseModelMode(modePayload) ?: return@withLock null
            Log.d(tag, "readFreqMode: ${frequency}Hz, $mode")
            frequency to mode
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820) return@withContext false
        ioMutex.withLock { setPttAndConfirm(enabled = true) }
    }

    override val supportsPttReadback get() = variant != IcomCivVariant.IC820
    override suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        if (!supportsPttReadback) return@withContext null
        ioMutex.withLock {
            val payload = sendAndReadResponse(IcomCivProtocol.buildReadPttCommand(),
                IcomCivProtocol.CMD_TRANSCEIVER_STATUS) { it.firstOrNull() == IcomCivProtocol.SUB_PTT }
            payload?.let(IcomCivProtocol::parsePttState)
        }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        // Cannot stop or confirm a transmission keyed by an external interface.
        if (variant == IcomCivVariant.IC820) return@withContext false
        ioMutex.withLock { setPttAndConfirm(enabled = false) }
    }

    // ── IRadioController – Icom duplex operations ──────────────────────────

    /** Modern radios switch bands via frequency; IC-820 needs explicit band access. */
    override suspend fun setBand(frequencyHz: Long): Boolean {
        if (variant != IcomCivVariant.IC820) return frequencyHz > 0L
        val band = ic820Band(frequencyHz) ?: return false
        return withContext(Dispatchers.IO) {
            ioMutex.withLock {
                // Access, not exchange: changing the controllable band must not swap TX/RX roles.
                if (!sendAndWaitAck(IcomCivProtocol.buildIc820BandAccessCommand(main = true))) return@withLock false
                val mainFrequency = readFrequencyLocked() ?: return@withLock false
                if (ic820Band(mainFrequency) == band) return@withLock true
                if (!sendAndWaitAck(IcomCivProtocol.buildIc820BandAccessCommand(main = false))) return@withLock false
                val subFrequency = readFrequencyLocked()
                if (subFrequency != null && ic820Band(subFrequency) == band) return@withLock true
                sendAndWaitAck(IcomCivProtocol.buildIc820BandAccessCommand(main = true))
                false
            }
        }
    }

    /** Select VFO-A (main/RX) or VFO-B (sub/TX). */
    override suspend fun setVfo(vfoA: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = selectVfoCommand(vfoA)
        Log.d(tag, "CMD selectVFO${if (vfoA) "A" else "B"} → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO while listening on main VFO).
     */
    override suspend fun setSplitMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val configured = if (usesDedicatedSatelliteMode) {
                if (enabled && !setOrdinarySplitLocked(enabled = false)) {
                    Log.w(tag, "Ordinary split OFF was not acknowledged before satellite mode")
                }
                setDedicatedSatelliteModeLocked(enabled)
            } else {
                if ((variant == IcomCivVariant.IC910 || variant == IcomCivVariant.IC9700) &&
                    !setDedicatedSatelliteModeLocked(enabled = false)) {
                    // Fallback must remain usable even when the optional SAT command is unsupported.
                    Log.w(tag, "Satellite mode OFF was not verified; continuing with ordinary split")
                }
                setOrdinarySplitLocked(enabled)
            }
            if (configured) duplexModeEnabled = enabled
            configured
        }
    }

    /** Configure VFO-A/RX and VFO-B/TX modes without changing the active RX VFO. */
    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = withContext(Dispatchers.IO) {
        if (rxMode?.let(::isModeSupported) == false || txMode?.let(::isModeSupported) == false) {
            Log.w(tag, "setSplitModes: mode is not supported by $variant: rx=$rxMode tx=$txMode")
            return@withContext false
        }
        if (usesManualVfoSelection) {
            return@withContext setManualVfoModes(rxMode, txMode)
        }
        val rxCommand = rxMode?.let { IcomCivProtocol.buildSetVfoModeCommand(selected = true, it, completeFields = variant == IcomCivVariant.IC705) }
        val txCommand = txMode?.let { IcomCivProtocol.buildSetVfoModeCommand(selected = false, it, completeFields = variant == IcomCivVariant.IC705) }
        if (rxMode != null && rxCommand == null || txMode != null && txCommand == null) {
            Log.w(tag, "setSplitModes: unsupported rx=$rxMode tx=$txMode")
            return@withContext false
        }
        ioMutex.withLock {
            if (!sendAndWaitAck(IcomCivProtocol.buildSelectVfoACommand())) return@withLock false
            if (rxCommand != null && !sendAndWaitAck(rxCommand)) return@withLock false
            if (txCommand != null && !sendAndWaitAck(txCommand)) return@withLock false
            if (variant == IcomCivVariant.IC705) {
                return@withLock (rxMode == null || verifyIc705ModeLocked(true, rxMode, IcomCivProtocol.isDataMode(rxMode))) &&
                    (txMode == null || verifyIc705ModeLocked(false, txMode, IcomCivProtocol.isDataMode(txMode)))
            }
            if (rxMode != null && readVfoModeLocked(selected = true) != normalizedMode(rxMode)) {
                return@withLock false
            }
            if (txMode != null && readVfoModeLocked(selected = false) != normalizedMode(txMode)) {
                return@withLock false
            }
            true
        }
    }

    /** Configure TX-side CTCSS as one CI-V transaction and always return to RX. */
    override suspend fun configureTxCtcss(toneHz: Double?): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820) return@withContext toneHz == null
        ioMutex.withLock {
            var rxRestored = false
            val configured = try {
                if (!sendAndWaitAck(selectVfoCommand(vfoA = false))) {
                    false
                } else if (toneHz != null) {
                    val toneCommand = IcomCivProtocol.buildSetCtcssToneCommand(toneHz)
                    val modeCommand = IcomCivProtocol.buildCtcssModeCommand(enabled = true)
                    val acknowledged = sendAndWaitAck(toneCommand) && sendAndWaitAck(modeCommand)
                    acknowledged
                } else {
                    val acknowledged = sendAndWaitAck(
                        IcomCivProtocol.buildCtcssModeCommand(enabled = false)
                    )
                    acknowledged
                }
            } finally {
                rxRestored = restoreRxVfo()
            }
            configured && rxRestored
        }
    }

    /**
     * Set the selected RX VFO frequency (CMD 0x25 sub 0x00). PTT does not
     * change which VFO is selected, so this remains the RX target while keyed.
     */
    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820 && ic820Band(frequencyHz) == null) return@withContext false
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                sendAndWaitAck(selectVfoCommand(vfoA = true)) &&
                    sendAndWaitAck(IcomCivProtocol.buildSetFreqCommand(frequencyHz)) &&
                    (variant != IcomCivVariant.IC820 || readFrequencyLocked() == frequencyHz)
            }
        }
        Log.d(tag, "setWorkingFrequency (0x25/00): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetWorkingFreqCommand(frequencyHz)
        Log.d(tag, "CMD setWorkingFreq → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Set TX VFO frequency via CMD 0x25 sub 0x01 (unselected VFO).
     * Sent every tracking cycle in split mode alongside [setWorkingFrequency].
     */
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (variant == IcomCivVariant.IC820 && ic820Band(frequencyHz) == null) return@withContext false
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                var rxRestored = false
                val frequencySet = try {
                    sendAndWaitAck(selectVfoCommand(vfoA = false)) &&
                        sendAndWaitAck(IcomCivProtocol.buildSetFreqCommand(frequencyHz)) &&
                        (variant != IcomCivVariant.IC820 || readFrequencyLocked() == frequencyHz)
                } finally {
                    rxRestored = restoreRxVfo()
                }
                frequencySet && rxRestored
            }
        }
        Log.d(tag, "setTxVfoFrequency (0x25/01): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetUnselectedVfoFreqCommand(frequencyHz)
        Log.d(tag, "CMD setTxVfoFreq → ${commandHex(cmd)}")
        ioMutex.withLock {
            sendAndWaitAck(cmd)
        }
    }

    /**
     * Read the frequency of the currently active VFO (CMD 0x25 sub 0x00).
     * Used for tuning detection in split mode.
     */
    override suspend fun readWorkingFrequency(): Long? = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                if (!sendAndWaitAck(selectVfoCommand(vfoA = true))) return@withLock null
                readFrequencyLocked()
            }
        }
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadWorkingFreqCommand()
            Log.d(tag, "CMD readWorkingFreq → ${commandHex(cmd)}")
            val payload = sendAndReadResponse(cmd, IcomCivProtocol.CMD_SELECTED_VFO_FREQ) {
                it.firstOrNull() == IcomCivProtocol.SUB_SELECTED_VFO
            } ?: return@withContext null
            // Response payload: [sub] [5 freq bytes] — CMD byte already stripped by parseResponse
            Log.d(tag, "readWorkingFreq: got ${payload.size} bytes: ${IcomCivProtocol.toHex(payload)}")
            val freq = IcomCivProtocol.parseVfoFrequencyPayload(payload, selected = true)
                ?: return@withContext null
            Log.d(tag, "readWorkingFreq: ${freq}Hz")
            freq
        }
    }

    /**
     * Read the frequency of the inactive/TX VFO (CMD 0x25 sub 0x01).
     * Used for tuning detection in split mode.
     */
    override suspend fun readTxVfoFrequency(): Long? = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                var rxRestored = false
                val frequency = try {
                    if (sendAndWaitAck(selectVfoCommand(vfoA = false))) {
                        readFrequencyLocked()
                    } else {
                        null
                    }
                } finally {
                    rxRestored = restoreRxVfo()
                }
                frequency?.takeIf { rxRestored }
            }
        }
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadTxVfoFreqCommand()
            Log.d(tag, "CMD readTxVfoFreq → ${commandHex(cmd)}")
            val payload = sendAndReadResponse(cmd, IcomCivProtocol.CMD_SELECTED_VFO_FREQ) {
                it.firstOrNull() == IcomCivProtocol.SUB_UNSELECTED_VFO
            } ?: return@withContext null
            // Response payload: [sub] [5 freq bytes] — CMD byte already stripped by parseResponse
            Log.d(tag, "readTxVfoFreq: got ${payload.size} bytes: ${IcomCivProtocol.toHex(payload)}")
            val freq = IcomCivProtocol.parseVfoFrequencyPayload(payload, selected = false)
                ?: return@withContext null
            Log.d(tag, "readTxVfoFreq: ${freq}Hz")
            freq
        }
    }

    // ── Internal I/O helpers ────────────────────────────────────────────────

    /**
     * Write [cmd] to the radio and drain the input stream for up to
     * [responseTimeoutMs], looking for an OK/NG acknowledgement frame.
     */
    private suspend fun sendAndWaitAck(cmd: ByteArray): Boolean {
        civSession?.let { session ->
            return session.request(addressCommand(cmd), IcomCivProtocol.ACK_OK,
                timeoutMillis = commandReplyTimeout(cmd), trace = handshaking)?.cmd == IcomCivProtocol.ACK_OK
        }
        if (!write(cmd)) return false
        delay(WRITE_SETTLE_MS)
        val buf = drainWithTimeout(responseTimeoutMs) {
            IcomCivProtocol.ackStatus(it, civAddress) != null
        }
        val ok = IcomCivProtocol.ackStatus(buf, civAddress) == true
        if (!ok) reportReplyFailure(cmd, buf)
        return ok
    }

    /**
     * Write [cmd] to the radio and drain the input stream for up to
     * [responseTimeoutMs], scanning for a response frame carrying [expectCmd].
     * Returns the payload bytes of that frame, or null on timeout/error.
     */
    private suspend fun sendAndReadResponse(cmd: ByteArray, expectCmd: Byte): ByteArray? {
        return sendAndReadResponse(cmd, expectCmd) { true }
    }

    private suspend fun sendAndReadResponse(
        cmd: ByteArray,
        expectCmd: Byte,
        payloadMatches: (ByteArray) -> Boolean
    ): ByteArray? {
        responseRejected = false
        civSession?.let { session ->
            val response = session.request(addressCommand(cmd), expectCmd,
                timeoutMillis = commandReplyTimeout(cmd), trace = handshaking, payloadMatches = payloadMatches)
            responseRejected = response?.cmd == IcomCivProtocol.ACK_NG
            return response?.takeIf { it.cmd == expectCmd }?.payload
        }
        if (!write(cmd)) return null
        delay(WRITE_SETTLE_MS)
        val buf      = drainWithTimeout(responseTimeoutMs) {
            IcomCivProtocol.parseResponse(it, expectCmd, civAddress, payloadMatches) != null ||
                IcomCivProtocol.ackStatus(it, civAddress) == false
        }
        val response = IcomCivProtocol.parseResponse(buf, expectCmd, civAddress, payloadMatches)
        if (response == null) {
            responseRejected = IcomCivProtocol.ackStatus(buf, civAddress) == false
            reportReplyFailure(cmd, buf)
        } else if (handshaking) {
            diagnose("reply/received", "command=${commandHex(cmd)} bytes=${IcomCivProtocol.toHex(buf.take(96).toByteArray())}")
        }
        return response?.payload
    }

    private fun reportReplyFailure(cmd: ByteArray, buf: ByteArray) {
        val stage = when {
            IcomCivProtocol.ackStatus(buf, civAddress) == false -> "reply/rejected"
            !isConnected || !transport.isConnected -> "reply/disconnected"
            else -> "reply/timeout"
        }
        diagnose(stage, "command=${commandHex(cmd)} received=${buf.size} bytes=${IcomCivProtocol.toHex(buf.take(96).toByteArray())} " +
            "io=${transport.lastIoFailure ?: "none"}", ControlDiagnosticSeverity.ERROR)
    }

    private fun diagnose(stage: String, message: String, severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "civ/$stage", message, severity)
    }

    private fun commandReplyTimeout(command: ByteArray): Long =
        if (command.getOrNull(4) == IcomCivProtocol.CMD_TRANSCEIVER_STATUS) 500L else 2_000L

    private suspend fun setIc705DataModeLocked(selected: Boolean, enabled: Boolean, baseMode: String): Boolean {
        val command = IcomCivProtocol.buildSetVfoModeCommand(selected, baseMode, completeFields = true) ?: return false
        command[7] = if (enabled) 1 else 0
        return sendAndWaitAck(command) && verifyIc705ModeLocked(selected, baseMode, enabled)
    }

    private suspend fun verifyIc705ModeLocked(selected: Boolean, mode: String, dataEnabled: Boolean): Boolean {
        val selector = if (selected) IcomCivProtocol.SUB_SELECTED_VFO else IcomCivProtocol.SUB_UNSELECTED_VFO
        repeat(3) {
            delay(50L)
            val payload = sendAndReadResponse(IcomCivProtocol.buildReadVfoModeCommand(selected),
                IcomCivProtocol.CMD_SELECTED_VFO_MODE) { it.firstOrNull() == selector }
            if (payload != null && payload.size >= 3 &&
                IcomCivProtocol.parseVfoModePayload(payload, selected) == normalizedMode(mode) &&
                payload[2] == (if (dataEnabled) 1 else 0).toByte()) return true
            if (!isConnected) return false
        }
        diagnose("mode/readback", "VFO=${if (selected) "RX" else "TX"} requested=$mode data=$dataEnabled readback did not match", ControlDiagnosticSeverity.ERROR)
        return false
    }

    private suspend fun setPttAndConfirm(enabled: Boolean): Boolean {
        val command = IcomCivProtocol.buildPttCommand(enabled)
        Log.d(tag, "CMD PTT ${if (enabled) "ON" else "OFF"} → ${commandHex(command)}")
        if (!sendAndWaitAck(command)) return false
        val response = sendAndReadResponse(
            IcomCivProtocol.buildReadPttCommand(),
            IcomCivProtocol.CMD_TRANSCEIVER_STATUS
        ) { it.firstOrNull() == IcomCivProtocol.SUB_PTT } ?: return false
        val confirmed = IcomCivProtocol.parsePttState(response)
        if (confirmed != enabled) {
            Log.e(tag, "PTT readback mismatch: requested=$enabled read=$confirmed")
            return false
        }
        return true
    }

    private suspend fun readVfoModeLocked(selected: Boolean): String? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadVfoModeCommand(selected),
            IcomCivProtocol.CMD_SELECTED_VFO_MODE
        ) {
            it.firstOrNull() == if (selected) {
                IcomCivProtocol.SUB_SELECTED_VFO
            } else {
                IcomCivProtocol.SUB_UNSELECTED_VFO
            }
        } ?: return null
        return IcomCivProtocol.parseVfoModePayload(payload, selected)
    }

    private fun normalizedMode(mode: String): String =
        IcomCivProtocol.modeSpec(mode)?.baseMode
            ?: if (mode.equals("AFSK", ignoreCase = true)) "FM" else mode.uppercase(Locale.US)

    private fun isModeSupported(mode: String): Boolean {
        return IcomCivProtocol.modeSpec(mode) != null &&
            (variant !in setOf(IcomCivVariant.IC910, IcomCivVariant.IC820) ||
                mode.uppercase(Locale.US) in IC910_MODES)
    }

    private fun selectVfoCommand(vfoA: Boolean): ByteArray = if (!usesDedicatedSatelliteMode) {
        if (vfoA) {
            IcomCivProtocol.buildSelectVfoACommand()
        } else {
            IcomCivProtocol.buildSelectVfoBCommand()
        }
    } else {
        if (vfoA) {
            IcomCivProtocol.buildSelectMainCommand()
        } else {
            IcomCivProtocol.buildSelectSubCommand()
        }
    }

    private suspend fun setManualVfoModes(rxMode: String?, txMode: String?): Boolean {
        val rxCommand = rxMode?.let(::buildModelModeCommand)
        val txCommand = txMode?.let(::buildModelModeCommand)
        if (rxMode != null && rxCommand == null || txMode != null && txCommand == null) return false
        return ioMutex.withLock {
            var rxRestored = false
            val successful = try {
                var successful = sendAndWaitAck(selectVfoCommand(vfoA = true))
                if (successful && rxMode != null) successful = if (variant == IcomCivVariant.IC910)
                    setIc910ModeLocked(rxMode) else sendAndWaitAck(checkNotNull(rxCommand))
                if (successful && rxMode != null && variant != IcomCivVariant.IC910) successful = readModeLocked() == normalizedMode(rxMode)
                if (successful) successful = sendAndWaitAck(selectVfoCommand(vfoA = false))
                if (successful && txMode != null) successful = if (variant == IcomCivVariant.IC910)
                    setIc910ModeLocked(txMode) else sendAndWaitAck(checkNotNull(txCommand))
                if (successful && txMode != null && variant != IcomCivVariant.IC910) successful = readModeLocked() == normalizedMode(txMode)
                successful
            } finally {
                rxRestored = restoreRxVfo()
            }
            successful && rxRestored
        }
    }

    private suspend fun restoreRxVfo(): Boolean = withContext(NonCancellable) {
        sendAndWaitAck(selectVfoCommand(vfoA = true))
    }

    private suspend fun setOrdinarySplitLocked(enabled: Boolean): Boolean {
        val command = if (variant == IcomCivVariant.IC820) {
            IcomCivProtocol.buildIc820SplitModeCommand(enabled)
        } else IcomCivProtocol.buildSplitModeCommand(enabled)
        Log.d(tag, "CMD split ${if (enabled) "ON" else "OFF"} → ${commandHex(command)}")
        if (!sendAndWaitAck(command)) return false
        if (variant != IcomCivVariant.IC705) return true
        val payload = sendAndReadResponse(IcomCivProtocol.buildReadSplitModeCommand(), IcomCivProtocol.CMD_DUPLEX_SPLIT)
        val verified = payload?.singleOrNull() == (if (enabled) 1 else 0).toByte()
        if (!verified) diagnose("split/readback", "requested=$enabled readback=${payload?.let(IcomCivProtocol::toHex) ?: "missing"}", ControlDiagnosticSeverity.ERROR)
        return verified
    }

    private suspend fun setDedicatedSatelliteModeLocked(enabled: Boolean): Boolean {
        val responseCommand: Byte
        val responseSubcommand: Byte
        val writeCommand: ByteArray
        val readCommand: ByteArray
        if (variant == IcomCivVariant.IC9700) {
            responseCommand = IcomCivProtocol.CMD_MISC_SETTING
            responseSubcommand = IcomCivProtocol.SUB_SATELLITE_MODE
            writeCommand = IcomCivProtocol.buildSatelliteModeCommand(enabled)
            readCommand = IcomCivProtocol.buildReadSatelliteModeCommand()
        } else {
            responseCommand = IcomCivProtocol.CMD_MEMORY_CONTROL
            responseSubcommand = IcomCivProtocol.SUB_IC910_SATELLITE_MODE
            writeCommand = IcomCivProtocol.buildIc910SatelliteModeCommand(enabled)
            readCommand = IcomCivProtocol.buildReadIc910SatelliteModeCommand()
        }
        Log.d(tag, "CMD satellite ${if (enabled) "ON" else "OFF"} → ${commandHex(writeCommand)}")
        if (!sendAndWaitAck(writeCommand)) return false
        val payload = sendAndReadResponse(readCommand, responseCommand) {
            it.firstOrNull() == responseSubcommand
        } ?: return false
        return IcomCivProtocol.parseSatelliteModeState(payload, responseSubcommand) == enabled
    }

    private suspend fun readFrequencyLocked(): Long? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadFreqCommand(),
            IcomCivProtocol.CMD_READ_FREQ
        ) ?: return null
        return parseModelFrequency(payload).also {
            if (it == null) diagnose("reply/invalid", "Invalid frequency payload=${IcomCivProtocol.toHex(payload.take(16).toByteArray())}", ControlDiagnosticSeverity.ERROR)
        }
    }

    private fun parseModelFrequency(payload: ByteArray): Long? =
        if (variant == IcomCivVariant.IC820) {
            IcomCivProtocol.parseIc820FrequencyPayload(payload)?.takeIf { ic820Band(it) != null }
        } else IcomCivProtocol.parseFrequencyPayload(payload)

    private fun ic820Band(frequencyHz: Long): Int? = when (frequencyHz) {
        in 136_000_000L..174_000_000L -> 2
        in 430_000_000L..450_000_000L -> 70
        else -> null
    }

    private suspend fun readModeLocked(): String? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadModeCommand(),
            IcomCivProtocol.CMD_READ_MODE
        ) ?: return null
        return parseModelMode(payload)
    }

    private fun parseModelMode(payload: ByteArray): String? =
        if (variant == IcomCivVariant.IC910 && payload.firstOrNull()?.toInt() in setOf(0x04,0x05)) "FM"
        else IcomCivProtocol.parseModePayload(payload)

    private fun buildModelModeCommand(mode: String): ByteArray? {
        val command=IcomCivProtocol.buildSetModeCommand(mode) ?: return null
        return if(variant==IcomCivVariant.IC820 && normalizedMode(mode)=="CW")
            command.dropLast(1).toByteArray()+byteArrayOf(0x01,IcomCivProtocol.END_OF_MSG) else command
    }

    private suspend fun setIc910ModeLocked(mode: String): Boolean {
        val original = IcomCivProtocol.buildSetModeCommand(mode) ?: return false
        // The IC-910 manual specifies FM=04; Hamlib records firmware also using 05.
        val commands = if (normalizedMode(mode) == "FM") listOf(
            original.copyOf().apply { this[5]=0x04 }, original
        ) else listOf(original)
        return commands.any { command ->
            sendAndWaitAck(command) && readModeLocked() == normalizedMode(mode)
        }
    }

    /**
     * Drain whatever bytes the radio has buffered within a [timeoutMs] window.
     * Exits early as soon as a complete CI-V frame addressed to us is present
     * in the buffer (i.e., FE FE E0 A4 … FD), so we don't waste the remaining
     * timeout on responses that already arrived.
     */
    private suspend fun drainWithTimeout(
        timeoutMs: Long,
        responseComplete: (ByteArray) -> Boolean
    ): ByteArray {
        val result   = mutableListOf<Byte>()
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            try {
                val chunk = transport.readAvailable(MAX_READ_CHUNK)
                if (chunk.isNotEmpty()) {
                    result.addAll(chunk.toList())
                    if (responseComplete(result.toByteArray())) break
                    // Bound unsolicited scope/broadcast traffic without losing a trailing partial frame.
                    if(result.size>4096) result.subList(0,result.size-4096).clear()
                } else {
                    if(!transport.isConnected) { isConnected=false;break }
                    delay(POLL_INTERVAL_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Drain error: ${e.message}")
                diagnose("read/failed", "${e.javaClass.simpleName}: ${e.message}", ControlDiagnosticSeverity.ERROR)
                isConnected = false
                break
            }
        }
        return result.toByteArray()
    }

    private suspend fun write(bytes: ByteArray): Boolean {
        return try {
            if (handshaking) diagnose("write", "command=${commandHex(bytes)} mode=${transport.connectionDescription}")
            if (!isConnected) false else transport.write(addressCommand(bytes)).also {
                if (!it) {
                    isConnected = false
                    diagnose("write/failed", "command=${commandHex(bytes)} io=${transport.lastIoFailure ?: "write returned false"}", ControlDiagnosticSeverity.ERROR)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Write error: ${e.message}")
            diagnose("write/failed", "${e.javaClass.simpleName}: ${e.message}", ControlDiagnosticSeverity.ERROR)
            isConnected = false
            false
        }
    }

    private fun commandHex(bytes: ByteArray): String =
        IcomCivProtocol.toHex(addressCommand(bytes))

    private fun addressCommand(bytes: ByteArray): ByteArray = bytes.copyOf().also { command ->
        if (command.size >= 4 && command[0] == IcomCivProtocol.PREAMBLE &&
            command[1] == IcomCivProtocol.PREAMBLE
        ) {
            command[2] = civAddress
        }
    }

    private companion object {
        val IC910_MODES = setOf("LSB", "USB", "CW", "FM")
        const val PTT_OFF_TIMEOUT_MS = 1_500L
        const val DUPLEX_OFF_TIMEOUT_MS = 1_500L
        const val MAX_READ_CHUNK = 4_096
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
