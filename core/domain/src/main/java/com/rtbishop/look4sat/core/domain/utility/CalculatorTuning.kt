package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos

enum class CalculatorFrequencyAnchor { GROUND_TX, GROUND_RX, SATELLITE }

/** Ground Hz for TX/RX anchors; satellite uplink Hz for the passband anchor. */
data class CalculatorTuning(val transponderUuid: String, val anchor: CalculatorFrequencyAnchor, val frequencyHz: Long)

data class CalculatorFrequencyPair(val txHz: Long, val rxHz: Long, val nominalTxHz: Long, val nominalRxHz: Long)

fun CalculatorTuning.frequencies(transponder: SatRadio, position: OrbitalPos, offsetHz: Long): CalculatorFrequencyPair? {
    if (transponderUuid != transponder.uuid || frequencyHz <= 0) return null
    val txRange = DopplerFrequencyCalculator.groundUplinkRange(transponder, position) ?: return null
    val rxRange = DopplerFrequencyCalculator.groundDownlinkRangeWithOffset(transponder, position, offsetHz) ?: return null
    val tx: Long
    val rx: Long
    when (anchor) {
        CalculatorFrequencyAnchor.GROUND_TX -> {
            tx = frequencyHz.coerceIn(txRange)
            rx = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(tx,transponder,position,offsetHz) ?: return null
        }
        CalculatorFrequencyAnchor.GROUND_RX -> {
            rx = frequencyHz.coerceIn(rxRange)
            tx = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(rx,transponder,position,offsetHz) ?: return null
        }
        CalculatorFrequencyAnchor.SATELLITE -> {
            val base = frequencyHz.coerceIn(minOf(transponder.uplinkLow!!,transponder.uplinkHigh!!),
                maxOf(transponder.uplinkLow,transponder.uplinkHigh))
            tx = position.getUplinkFreq(base)
            rx = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(tx,transponder,position,offsetHz) ?: return null
        }
    }
    val appliedTx = tx.coerceIn(txRange)
    val appliedRx = rx.coerceIn(rxRange)
    val nominalTx = if (anchor == CalculatorFrequencyAnchor.SATELLITE)
        frequencyHz.coerceIn(minOf(transponder.uplinkLow!!,transponder.uplinkHigh!!), maxOf(transponder.uplinkLow,transponder.uplinkHigh))
    else position.getDownlinkFreq(appliedTx)
    return CalculatorFrequencyPair(appliedTx,appliedRx,nominalTx,position.getUplinkFreq(appliedRx))
}
