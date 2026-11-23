/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.time

import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClockSource {
    SYSTEM,
    NTP,
    GNSS,
    HOLDOVER
}

data class ClockSnapshot(
    val utcMillis: Long,
    val monotonicNanos: Long,
    val offsetMillis: Double,
    val driftPpm: Double,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val sampleAgeMillis: Long,
    val healthy: Boolean,
    val detail: String = "",
    val roundTripDelayMillis: Double? = null,
    val consensusMembers: Int = 0,
    val lastRejectedReason: String = ""
)

data class ClockSample(
    val utcMillis: Double,
    val monotonicNanos: Long,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val detail: String = "",
    val roundTripDelayMillis: Double? = null,
    val consensusMembers: Int = 1
)

data class ClockHealthPolicy(
    val healthyUncertaintyMillis: Double = 1_000.0,
    val ft4AutomaticTxUncertaintyMillis: Double = 250.0,
    val maximumSampleAgeMillis: Long = 30 * 60 * 1_000L,
    val holdoverAfterMillis: Long = 2 * 60 * 1_000L,
    val maximumDriftPpm: Double = 50.0,
    val gnssPriorityMillis: Long = 2 * 60 * 1_000L
)

interface MonotonicTimeSource {
    fun elapsedRealtimeNanos(): Long
    fun wallClockMillis(): Long
}

object JvmMonotonicTimeSource : MonotonicTimeSource {
    override fun elapsedRealtimeNanos(): Long = System.nanoTime()
    override fun wallClockMillis(): Long = System.currentTimeMillis()
}

interface IDisciplinedClock {
    val state: StateFlow<ClockSnapshot>
    fun snapshot(): ClockSnapshot
    fun nowMillis(): Long
    fun utcMillisAt(monotonicNanos: Long): Long
    fun submitSample(sample: ClockSample): Boolean
    fun refresh(): ClockSnapshot
    fun automaticFt4TransmitAllowed(): Boolean
    fun automaticFt4TransmitBlockReason(): String
}

/** 在单调时钟上维护应用 UTC；系统 wall clock 跳变不会改变运行中的 FT4 时隙。 */
class SystemDisciplinedClock(
    private val timeSource: MonotonicTimeSource = JvmMonotonicTimeSource,
    private val policy: ClockHealthPolicy = ClockHealthPolicy()
) : IDisciplinedClock {
    private val lock = Any()
    private var anchorMonotonicNanos = timeSource.elapsedRealtimeNanos()
    private var anchorUtcMillis = timeSource.wallClockMillis().toDouble()
    private var acceptedSampleMonotonicNanos = anchorMonotonicNanos
    private var acceptedSampleUtcMillis = anchorUtcMillis
    private var baseUncertaintyMillis = 5_000.0
    private var driftPpm = 0.0
    private var disciplinedSource = ClockSource.SYSTEM
    private var detail = "等待 NTP 或可信 GNSS 时间"
    private var roundTripDelayMillis: Double? = null
    private var consensusMembers = 0
    private var lastRejectedReason = ""
    private var lastGnssSampleMonotonicNanos = Long.MIN_VALUE
    private var reacquireResidualMillis: Double? = null
    private var reacquireSampleCount = 0
    private var reacquireFirstMonotonicNanos = Long.MIN_VALUE
    private var lastPublishedUtcMillis = anchorUtcMillis.toLong()

    private val mutableState = MutableStateFlow(snapshotLocked(anchorMonotonicNanos))
    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    override fun snapshot(): ClockSnapshot = synchronized(lock) {
        refreshLocked(timeSource.elapsedRealtimeNanos())
    }

    override fun nowMillis(): Long = synchronized(lock) {
        monotonicUtcLocked(utcAtLocked(timeSource.elapsedRealtimeNanos()))
    }

    override fun utcMillisAt(monotonicNanos: Long): Long = synchronized(lock) {
        utcAtLocked(monotonicNanos).toLong()
    }

    override fun submitSample(sample: ClockSample): Boolean = synchronized(lock) {
        require(sample.source == ClockSource.NTP || sample.source == ClockSource.GNSS) {
            "时间纪律样本只能来自 NTP 或 GNSS"
        }
        val nowMonotonic = timeSource.elapsedRealtimeNanos()
        if (!sample.utcMillis.isFinite() || !sample.uncertaintyMillis.isFinite() ||
            sample.uncertaintyMillis < 0.0
        ) {
            return rejectLocked("样本数值无效", nowMonotonic)
        }
        val sampleAgeNanos = nowMonotonic - sample.monotonicNanos
        if (sampleAgeNanos < -100_000_000L || sampleAgeNanos > 60_000_000_000L) {
            return rejectLocked("样本单调时间不在允许窗口内", nowMonotonic)
        }

        val predictedAtSample = utcAtLocked(sample.monotonicNanos)
        val residualMillis = sample.utcMillis - predictedAtSample
        if (sample.source == ClockSource.NTP && gnssIsFresh(nowMonotonic)) {
            return crossCheckNtpAgainstGnss(sample, residualMillis, nowMonotonic)
        }

        val currentlyTrusted = disciplinedSource != ClockSource.SYSTEM
        var reacquiring = false
        if (currentlyTrusted && abs(residualMillis) > MAX_TRUSTED_SAMPLE_RESIDUAL_MS) {
            reacquiring = recordReacquireCandidateLocked(residualMillis, sample, nowMonotonic)
            if (!reacquiring) {
                return rejectLocked(
                    "可信时钟大残差 ${residualMillis.toInt()}ms，等待多源持续确认",
                    nowMonotonic
                )
            }
        }

        val elapsedSincePreviousMs =
            (sample.monotonicNanos - acceptedSampleMonotonicNanos) / 1_000_000.0
        if (currentlyTrusted && !reacquiring && elapsedSincePreviousMs >= MIN_DRIFT_WINDOW_MS) {
            val utcElapsedMs = sample.utcMillis - acceptedSampleUtcMillis
            val measuredPpm = ((utcElapsedMs / elapsedSincePreviousMs) - 1.0) * 1_000_000.0
            if (measuredPpm.isFinite() && abs(measuredPpm) <= MAX_MEASURED_DRIFT_PPM) {
                driftPpm = (driftPpm * 0.75 + measuredPpm * 0.25)
                    .coerceIn(-policy.maximumDriftPpm, policy.maximumDriftPpm)
            }
        }

        val correction = if (!currentlyTrusted || reacquiring) {
            residualMillis
        } else {
            residualMillis.coerceIn(-MAX_SLEW_STEP_MS, MAX_SLEW_STEP_MS) * SLEW_GAIN
        }
        anchorMonotonicNanos = sample.monotonicNanos
        anchorUtcMillis = predictedAtSample + correction
        acceptedSampleMonotonicNanos = sample.monotonicNanos
        acceptedSampleUtcMillis = sample.utcMillis
        baseUncertaintyMillis = if (reacquiring) {
            max(REACQUIRE_INITIAL_UNCERTAINTY_MS, sample.uncertaintyMillis)
        } else {
            max(sample.uncertaintyMillis, abs(residualMillis - correction))
        }
        disciplinedSource = sample.source
        if (sample.source == ClockSource.GNSS) lastGnssSampleMonotonicNanos = sample.monotonicNanos
        detail = sample.detail
        roundTripDelayMillis = sample.roundTripDelayMillis
        consensusMembers = sample.consensusMembers
        lastRejectedReason = ""
        clearReacquireCandidateLocked()
        refreshLocked(nowMonotonic)
        true
    }

    override fun refresh(): ClockSnapshot = synchronized(lock) {
        val nowMono = timeSource.elapsedRealtimeNanos()
        if (disciplinedSource == ClockSource.SYSTEM) {
            val wallResidual = timeSource.wallClockMillis() - utcAtLocked(nowMono)
            if (abs(wallResidual) > WALL_CLOCK_JUMP_MS) {
                anchorMonotonicNanos = nowMono
                anchorUtcMillis = timeSource.wallClockMillis().toDouble()
                acceptedSampleMonotonicNanos = nowMono
                acceptedSampleUtcMillis = anchorUtcMillis
                baseUncertaintyMillis = max(5_000.0, abs(wallResidual))
                detail = "检测到系统时钟跳变，等待重新校准"
            }
        }
        refreshLocked(nowMono)
    }

    override fun automaticFt4TransmitAllowed(): Boolean {
        val current = snapshot()
        return current.source != ClockSource.SYSTEM && current.healthy &&
            current.uncertaintyMillis <= policy.ft4AutomaticTxUncertaintyMillis &&
            current.sampleAgeMillis <= policy.maximumSampleAgeMillis
    }

    override fun automaticFt4TransmitBlockReason(): String {
        val current = snapshot()
        return when {
            current.source == ClockSource.SYSTEM -> "尚未取得可信 NTP/GNSS 时间"
            current.sampleAgeMillis > policy.maximumSampleAgeMillis -> "时间样本已过期"
            current.uncertaintyMillis > policy.ft4AutomaticTxUncertaintyMillis ->
                "FT4 时间误差范围超过 250ms 自动发射门限"
            !current.healthy -> "应用 UTC 时钟状态不健康"
            else -> ""
        }
    }

    private fun crossCheckNtpAgainstGnss(
        sample: ClockSample,
        residualMillis: Double,
        nowMonotonic: Long
    ): Boolean {
        val agreementLimit = max(NTP_GNSS_AGREEMENT_MS, sample.uncertaintyMillis * 4.0)
        if (abs(residualMillis) > agreementLimit) {
            return rejectLocked("NTP 与 GNSS 相差 ${residualMillis.toInt()}ms", nowMonotonic)
        }
        roundTripDelayMillis = sample.roundTripDelayMillis
        consensusMembers = sample.consensusMembers
        detail = "GNSS 时间；NTP ${sample.consensusMembers} 源交叉校验通过"
        lastRejectedReason = ""
        refreshLocked(nowMonotonic)
        return true
    }

    private fun gnssIsFresh(nowMonotonic: Long): Boolean =
        lastGnssSampleMonotonicNanos != Long.MIN_VALUE &&
            nowMonotonic - lastGnssSampleMonotonicNanos <= policy.gnssPriorityMillis * 1_000_000L

    private fun refreshLocked(nowMonotonic: Long): ClockSnapshot {
        val snapshot = snapshotLocked(nowMonotonic)
        mutableState.value = snapshot
        return snapshot
    }

    private fun snapshotLocked(nowMonotonic: Long): ClockSnapshot {
        val ageMillis = max(0L, (nowMonotonic - acceptedSampleMonotonicNanos) / 1_000_000L)
        val uncertainty = baseUncertaintyMillis +
            ageMillis * policy.maximumDriftPpm / 1_000_000.0
        val effectiveSource = if (
            disciplinedSource != ClockSource.SYSTEM && ageMillis > policy.holdoverAfterMillis
        ) ClockSource.HOLDOVER else disciplinedSource
        val rawUtc = utcAtLocked(nowMonotonic)
        val utc = monotonicUtcLocked(rawUtc)
        val effectiveUncertainty = uncertainty + max(0.0, utc - rawUtc)
        val healthy = disciplinedSource != ClockSource.SYSTEM &&
            ageMillis <= policy.maximumSampleAgeMillis &&
            effectiveUncertainty <= policy.healthyUncertaintyMillis
        return ClockSnapshot(
            utcMillis = utc,
            monotonicNanos = nowMonotonic,
            offsetMillis = (utc - timeSource.wallClockMillis()).toDouble(),
            driftPpm = driftPpm,
            uncertaintyMillis = effectiveUncertainty,
            source = effectiveSource,
            sampleAgeMillis = ageMillis,
            healthy = healthy,
            detail = detail,
            roundTripDelayMillis = roundTripDelayMillis,
            consensusMembers = consensusMembers,
            lastRejectedReason = lastRejectedReason
        )
    }

    private fun rejectLocked(reason: String, nowMonotonic: Long): Boolean {
        lastRejectedReason = reason
        refreshLocked(nowMonotonic)
        return false
    }

    private fun recordReacquireCandidateLocked(
        residualMillis: Double,
        sample: ClockSample,
        nowMonotonic: Long
    ): Boolean {
        val previous = reacquireResidualMillis
        val expired = reacquireFirstMonotonicNanos == Long.MIN_VALUE ||
            nowMonotonic - reacquireFirstMonotonicNanos > REACQUIRE_WINDOW_NANOS
        val agrees = previous != null && residualMillis * previous > 0.0 &&
            abs(residualMillis - previous) <= REACQUIRE_AGREEMENT_MS
        val trustedConsensus = sample.source == ClockSource.GNSS ||
            sample.consensusMembers >= MIN_REACQUIRE_CONSENSUS_MEMBERS
        if (expired || !agrees || !trustedConsensus) {
            reacquireResidualMillis = residualMillis
            reacquireSampleCount = 1
            reacquireFirstMonotonicNanos = nowMonotonic
            return false
        }
        reacquireResidualMillis = (previous + residualMillis) / 2.0
        reacquireSampleCount++
        return reacquireSampleCount >= REACQUIRE_REQUIRED_SAMPLES
    }

    private fun clearReacquireCandidateLocked() {
        reacquireResidualMillis = null
        reacquireSampleCount = 0
        reacquireFirstMonotonicNanos = Long.MIN_VALUE
    }

    private fun monotonicUtcLocked(rawUtcMillis: Double): Long {
        val candidate = rawUtcMillis.toLong()
        if (candidate > lastPublishedUtcMillis) lastPublishedUtcMillis = candidate
        return lastPublishedUtcMillis
    }

    private fun utcAtLocked(monotonicNanos: Long): Double {
        val elapsedMillis = (monotonicNanos - anchorMonotonicNanos) / 1_000_000.0
        return anchorUtcMillis + elapsedMillis * (1.0 + driftPpm / 1_000_000.0)
    }

    private companion object {
        const val MAX_TRUSTED_SAMPLE_RESIDUAL_MS = 2_000.0
        const val MAX_MEASURED_DRIFT_PPM = 500.0
        const val MIN_DRIFT_WINDOW_MS = 60_000.0
        const val MAX_SLEW_STEP_MS = 100.0
        const val SLEW_GAIN = 0.25
        const val WALL_CLOCK_JUMP_MS = 500.0
        const val REACQUIRE_REQUIRED_SAMPLES = 3
        const val MIN_REACQUIRE_CONSENSUS_MEMBERS = 2
        const val REACQUIRE_AGREEMENT_MS = 250.0
        const val REACQUIRE_WINDOW_NANOS = 60_000_000_000L
        const val REACQUIRE_INITIAL_UNCERTAINTY_MS = 2_000.0
        const val NTP_GNSS_AGREEMENT_MS = 250.0
    }
}
