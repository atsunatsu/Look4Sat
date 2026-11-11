package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class PresetTuningResult(val rxHz: Long? = null, val txHz: Long? = null, val success: Boolean = true)

/** Legacy Yaesu CAT cannot address an inactive VFO. Never blindly toggle an unknown A/B state. */
internal fun presetHalfDuplexLeg(observed: Long, rxHz: Long?, txHz: Long?): Boolean? {
    val rxDistance = rxHz?.let { abs(observed - it) } ?: Long.MAX_VALUE
    val txDistance = txHz?.let { abs(observed - it) } ?: Long.MAX_VALUE
    if (minOf(rxDistance, txDistance) > 1_000_000L) return null
    if (rxHz != null && txHz != null && abs(rxDistance - txDistance) < 10_000L) return null
    return rxDistance < txDistance
}

/**
 * IC-820H uses SUB for RX and MAIN for TX. Select by role, never by
 * frequency-band guessing: both legs can be tuned independently through 07 D0/D1 + 05.
 * Verify the final pair after both writes, since the radio's front-panel SAT
 * tracking can couple the two frequencies even when each CI-V write was ACKed.
 */
internal suspend fun tunePresetHalfDuplex(
    radio: IRadioController, ic820: Boolean, rxHz: Long?, txHz: Long?
): PresetTuningResult {
    if (!ic820) {
        val observed = radio.readFrequencyAndMode()?.first ?: return PresetTuningResult(success = false)
        return when (presetHalfDuplexLeg(observed, rxHz, txHz)) {
            true -> if (radio.setFrequency(checkNotNull(rxHz))) PresetTuningResult(rxHz = rxHz)
                else PresetTuningResult(success = false)
            false -> if (radio.setFrequency(checkNotNull(txHz))) PresetTuningResult(txHz = txHz)
                else PresetTuningResult(success = false)
            null -> PresetTuningResult(success = false)
        }
    }
    if (radio.supportsAtomicPair && rxHz != null) {
        return if (radio.setTrackingFrequencies(rxHz, txHz) == FrequencyPairResult.APPLIED)
            PresetTuningResult(rxHz, txHz)
        else PresetTuningResult(success = false)
    }
    var appliedTx: Long? = null
    var appliedRx: Long? = null
    var successful = true
    var rxRestored = false
    try {
        if (txHz != null) {
            successful = radio.setTxVfoFrequency(txHz)
            if (successful) appliedTx = txHz
        }
        if (successful && rxHz != null) {
            successful = radio.setWorkingFrequency(rxHz)
            if (successful) appliedRx = rxHz
        }
        // An ACK/individual readback is not enough if SATL-N/REV moved the other leg.
        if (successful && txHz != null) successful = radio.readTxVfoFrequency() == txHz
        if (successful && rxHz != null) successful = radio.readWorkingFrequency() == rxHz
    } finally {
        rxRestored = withContext(NonCancellable) {
            withTimeoutOrNull(1_500L) { radio.setVfo(vfoA = true) } == true
        }
    }
    return if (successful && rxRestored) PresetTuningResult(appliedRx, appliedTx)
        else PresetTuningResult(success = false)
}
