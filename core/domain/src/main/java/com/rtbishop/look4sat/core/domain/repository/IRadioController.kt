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
package com.rtbishop.look4sat.core.domain.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface IRadioController {
    val commandTimeoutMillis: Long get() = 3_000L

    val remoteState: kotlinx.coroutines.flow.StateFlow<RemoteRadioState>? get() = null
    val supportsAtomicPair: Boolean get() = false
    suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult = FrequencyPairResult.UNSUPPORTED
    suspend fun startCw(text: String, wpm: Int): Boolean = false
    suspend fun updateTrackingDisplay(display: RadioTrackingDisplay): Boolean = true
    suspend fun prepareTrackingContext(contextKey: String): Boolean = true
    suspend fun selectSatelliteProfile(name: String, transponderUuid: String): Boolean =
        prepareTrackingContext(radioContextKey(transponderUuid))
    suspend fun updateTrackingBases(bases: RadioBaseFrequencies): Boolean = true
    suspend fun profileStoreCommand(data:ByteArray):ByteArray?=null
    suspend fun setNativeCwOptions(options: NativeCwOptions): Boolean = false
    suspend fun handsetMacroCommand(data: ByteArray): ByteArray? = null
    suspend fun refreshNativeDiagnostics(): Boolean = false

    val isConnected: Boolean

    /** Null means a supported query failed, not a confirmed receive state. */
    val supportsPttReadback: Boolean get() = false
    suspend fun readPttState(): Boolean? = null

    suspend fun connect(): Boolean

    suspend fun disconnect()
    suspend fun setAudioForeground(enabled: Boolean) = Unit
    suspend fun setRadioMonitor(enabled: Boolean): Boolean = false
    suspend fun recordingAction(action: com.rtbishop.look4sat.core.domain.model.RadioRecordingAction): Boolean = false
    suspend fun saveRecording(destinationUri: String): Boolean = false

    suspend fun setFrequency(frequencyHz: Long): Boolean

    suspend fun setMode(mode: String): Boolean

    /** Enable a radio's DATA path while preserving the requested sideband for later restore. */
    suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean = !enabled

    /** Configure the transmit side's DATA path in single-radio duplex operation. */
    suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean =
        setDataMode(enabled, baseMode)

    suspend fun setCtcssMode(enabled: Boolean): Boolean

    suspend fun setCtcssTone(toneHz: Double): Boolean

    suspend fun readFrequencyAndMode(): Pair<Long, String>?

    suspend fun pttOn(): Boolean

    suspend fun pttOff(): Boolean

    /** Generation captured while arming a transmission, before PTT can be requested. */
    fun pttSafetyGeneration(): Long = 0L

    /** Rejects PTT-ON if an emergency stop invalidated the captured generation. */
    suspend fun pttOnIfGeneration(expectedGeneration: Long): Boolean =
        expectedGeneration == pttSafetyGeneration() && pttOn()

    /** Invalidates queued PTT-ON work before an emergency PTT-OFF is enqueued. */
    fun invalidatePendingPttOn() = Unit

    // ── Extended duplex operations ────────────────────────────────

    /**
     * Prepare the radio for the band containing [frequencyHz], when required.
     * The default reports that explicit band preparation is unsupported.
     */
    suspend fun setBand(frequencyHz: Long): Boolean = false

    /**
     * Select the active VFO.
     * @param vfoA true → VFO-A (main/RX), false → VFO-B (sub/TX in split).
     */
    suspend fun setVfo(vfoA: Boolean): Boolean = false

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO, RX on main VFO).
     * Default: not supported.
     */
    suspend fun setSplitMode(enabled: Boolean): Boolean = false

    /**
     * Configure both sides of a split setup as one serialized transaction.
     * Implementations should leave VFO-A selected and verify the written modes.
     */
    suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean {
        if (!setVfo(vfoA = true)) return false
        if (rxMode != null && !setMode(rxMode)) return false
        if (!setVfo(vfoA = false)) return false
        if (txMode != null && !setMode(txMode)) return false
        return setVfo(vfoA = true)
    }

    /**
     * Configure the TX side's CTCSS state and leave the RX side selected.
     * Serialized controller wrappers execute this complete sequence atomically.
     */
    suspend fun configureTxCtcss(toneHz: Double?): Boolean {
        var rxRestored = false
        val configured = try {
            if (!setVfo(vfoA = false)) {
                false
            } else if (toneHz != null) {
                setCtcssTone(toneHz) && setCtcssMode(true)
            } else {
                setCtcssMode(false)
            }
        } finally {
            rxRestored = withContext(NonCancellable) { setVfo(vfoA = true) }
        }
        return configured && rxRestored
    }

    /**
     * Set the current RX side (IC-705 selected VFO or IC-9700 MAIN).
     * Default: delegates to [setFrequency].
     */
    suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = setFrequency(frequencyHz)

    /**
     * Set the TX side (IC-705 unselected VFO or IC-9700 SUB).
     * Sent every tracking cycle alongside [setWorkingFrequency] in split mode.
     * Default: delegates to [setWorkingFrequency].
     */
    suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = setWorkingFrequency(frequencyHz)

    /**
     * Read the current RX side.
     * Default: delegates to [readFrequencyAndMode].
     */
    suspend fun readWorkingFrequency(): Long? = readFrequencyAndMode()?.first

    /**
     * Read the TX side.
     * Used for tuning detection in split mode.
     * Default: delegates to [readWorkingFrequency].
     */
    suspend fun readTxVfoFrequency(): Long? = readWorkingFrequency()
}
