/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.utility

import kotlin.math.abs

enum class DialLeg {
    TX,
    RX
}

enum class DialFollowRejection {
    INVALID_FREQUENCY,
    WRONG_BAND
}

data class DialFollowState(
    val activeLeg: DialLeg? = null,
    val candidateFrequencyHz: Long? = null,
    val stableSinceMillis: Long? = null
)

data class DialFollowResult(
    val state: DialFollowState,
    val acceptedLeg: DialLeg? = null,
    val acceptedFrequencyHz: Long? = null,
    val rejection: DialFollowRejection? = null
)

object DialFollowPolicy {
    fun update(
        state: DialFollowState,
        leg: DialLeg,
        observedFrequencyHz: Long,
        commandedFrequencyHz: Long?,
        nowMillis: Long,
        deadbandHz: Long,
        settleMillis: Long
    ): DialFollowResult {
        require(deadbandHz > 0L)
        require(settleMillis >= 0L)
        if (observedFrequencyHz <= 0L || commandedFrequencyHz == null || commandedFrequencyHz <= 0L) {
            return DialFollowResult(DialFollowState(), rejection = DialFollowRejection.INVALID_FREQUENCY)
        }
        if (absDifference(observedFrequencyHz, commandedFrequencyHz) < deadbandHz) {
            return DialFollowResult(DialFollowState())
        }
        if (!isSameOperatingBand(observedFrequencyHz, commandedFrequencyHz)) {
            return DialFollowResult(DialFollowState(), rejection = DialFollowRejection.WRONG_BAND)
        }

        val sameCandidate = state.activeLeg == leg &&
            state.candidateFrequencyHz?.let { absDifference(observedFrequencyHz, it) < deadbandHz } == true
        val nextState = if (sameCandidate) {
            state.copy(candidateFrequencyHz = observedFrequencyHz)
        } else {
            DialFollowState(
                activeLeg = leg,
                candidateFrequencyHz = observedFrequencyHz,
                stableSinceMillis = nowMillis
            )
        }
        val stableSince = nextState.stableSinceMillis ?: nowMillis
        return if (nowMillis - stableSince >= settleMillis) {
            DialFollowResult(
                state = DialFollowState(),
                acceptedLeg = leg,
                acceptedFrequencyHz = observedFrequencyHz
            )
        } else {
            DialFollowResult(state = nextState)
        }
    }

    fun isSameOperatingBand(firstHz: Long, secondHz: Long): Boolean {
        if (firstHz <= 0L || secondHz <= 0L) return false
        val firstBand = operatingBand(firstHz)
        val secondBand = operatingBand(secondHz)
        return if (firstBand != null || secondBand != null) {
            firstBand != null && firstBand == secondBand
        } else {
            absDifference(firstHz, secondHz) <= UNKNOWN_BAND_WINDOW_HZ
        }
    }

    private fun operatingBand(frequencyHz: Long): Int? = when (frequencyHz) {
        in 100_000L..30_000_000L -> 0
        in 30_000_001L..88_000_000L -> 1
        in 108_000_000L..250_000_000L -> 2
        in 300_000_000L..1_000_000_000L -> 3
        in 1_000_000_001L..3_000_000_000L -> 4
        else -> null
    }

    private fun absDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first

    private const val UNKNOWN_BAND_WINDOW_HZ = 10_000_000L
}
