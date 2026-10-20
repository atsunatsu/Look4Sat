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

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 所有 CAT、频率、模式、tone 与 PTT 操作共用的串行命令通道。 */
class RadioCommandActor(
    scope: CoroutineScope,
    private val stateChanged: (busy: Boolean, failure: String?) -> Unit
) {
    private enum class Kind { NORMAL, PTT_ON, PTT_OFF }

    private class Command<T>(
        val operation: suspend () -> T,
        val result: CompletableDeferred<Result<T>>,
        val kind: Kind,
        val pttGeneration: Long,
        val deadlineNanos: Long,
        val timeoutMillis: Long
    )

    private val queue = Channel<Command<*>>(NORMAL_QUEUE_CAPACITY)
    private val urgentQueue = Channel<Command<*>>(URGENT_QUEUE_CAPACITY)
    private val pttGeneration = AtomicLong(0L)
    private val commandStateLock = Any()
    private var urgentPending = 0
    private var activeInterruptible: CompletableDeferred<*>? = null
    private var activePttOn: CompletableDeferred<*>? = null

    init {
        scope.launch {
            while (isActive) executeUnchecked(nextCommand())
        }
    }

    suspend fun <T> execute(
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        operation: suspend () -> T
    ): T = enqueue(Kind.NORMAL, timeoutMillis, operation)

    fun currentPttGeneration(): Long = pttGeneration.get()

    suspend fun <T> executePttOn(
        expectedGeneration: Long = currentPttGeneration(),
        operation: suspend () -> T
    ): T = enqueue(
        kind = Kind.PTT_ON,
        timeoutMillis = PTT_COMMAND_TIMEOUT_MILLIS,
        operation = operation,
        expectedPttGeneration = expectedGeneration
    )

    suspend fun <T> executePttOff(operation: suspend () -> T): T {
        synchronized(commandStateLock) {
            pttGeneration.incrementAndGet()
            urgentPending++
        }
        return enqueue(Kind.PTT_OFF, PTT_COMMAND_TIMEOUT_MILLIS, operation, urgent = true)
    }

    fun invalidatePendingPttOn() {
        synchronized(commandStateLock) {
            pttGeneration.incrementAndGet()
        }
    }

    private suspend fun <T> enqueue(
        kind: Kind,
        timeoutMillis: Long,
        operation: suspend () -> T,
        urgent: Boolean = false,
        expectedPttGeneration: Long = pttGeneration.get()
    ): T {
        val result = CompletableDeferred<Result<T>>(
            if (kind == Kind.PTT_OFF) null else coroutineContext[Job]
        )
        val command = Command(
            operation = operation,
            result = result,
            kind = kind,
            pttGeneration = expectedPttGeneration,
            deadlineNanos = if (kind == Kind.PTT_OFF) {
                Long.MAX_VALUE
            } else {
                System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            },
            timeoutMillis = timeoutMillis
        )
        if (urgent) {
            withContext(NonCancellable) { urgentQueue.send(command) }
        } else {
            queue.send(command)
        }
        return result.await().getOrThrow()
    }

    private suspend fun nextCommand(): Command<*> =
        urgentQueue.tryReceive().getOrNull()
            ?: queue.tryReceive().getOrNull()
            ?: select {
                urgentQueue.onReceive { it }
                queue.onReceive { it }
            }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeUnchecked(command: Command<*>) {
        command as Command<Any?>
        if (command.kind != Kind.PTT_OFF) prioritizeUrgentAndMarkActive(command)
        try {
            val skipReason = when {
                !command.result.isActive -> "Command caller was cancelled"
                System.nanoTime() >= command.deadlineNanos -> "Radio command expired in queue"
                command.kind == Kind.PTT_ON && command.pttGeneration != pttGeneration.get() ->
                    "PTT ON command was invalidated"
                else -> null
            }
            if (skipReason != null) {
                command.result.complete(Result.failure(CancellationException(skipReason)))
                return
            }
            stateChanged(true, null)
            val result = runCatching {
                withContext(command.result) {
                    withTimeout(command.timeoutMillis) { command.operation() }
                }
            }
            val error = result.exceptionOrNull()
            val reportableFailure = when (error) {
                null -> null
                is TimeoutCancellationException -> "Radio command timed out"
                is CancellationException -> null
                else -> error.message ?: error.javaClass.simpleName
            }
            stateChanged(false, reportableFailure)
            command.result.complete(result)
        } finally {
            synchronized(commandStateLock) {
                if (activeInterruptible === command.result) activeInterruptible = null
                if (activePttOn === command.result) activePttOn = null
                if (command.kind == Kind.PTT_OFF) urgentPending = (urgentPending - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun prioritizeUrgentAndMarkActive(command: Command<*>) {
        while (true) {
            val canStart = synchronized(commandStateLock) {
                if (urgentPending == 0) {
                    activeInterruptible = command.result
                    if (command.kind == Kind.PTT_ON) activePttOn = command.result
                    true
                } else {
                    false
                }
            }
            if (canStart) return
            executeUnchecked(urgentQueue.receive())
        }
    }

    private companion object {
        const val NORMAL_QUEUE_CAPACITY = 64
        const val URGENT_QUEUE_CAPACITY = 8
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 3_000L
        const val PTT_COMMAND_TIMEOUT_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

class SerialRadioController(
    private val delegate: IRadioController,
    private val actor: RadioCommandActor
) : IRadioController {
    override val commandTimeoutMillis get() = delegate.commandTimeoutMillis
    private suspend fun <T> execute(operation: suspend () -> T): T =
        actor.execute(delegate.commandTimeoutMillis, operation)
    override val remoteState get() = delegate.remoteState
    override val supportsAtomicPair get() = delegate.supportsAtomicPair
    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?) =
        execute { delegate.setTrackingFrequencies(rxHz, txHz) }
    override suspend fun startCw(text: String, wpm: Int) =
        actor.executePttOn { delegate.startCw(text, wpm) }
    override suspend fun updateTrackingDisplay(display: com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay) =
        execute { delegate.updateTrackingDisplay(display) }
    override suspend fun prepareTrackingContext(contextKey: String) = execute { delegate.prepareTrackingContext(contextKey) }
    override suspend fun selectSatelliteProfile(name: String, transponderUuid: String) =
        execute { delegate.selectSatelliteProfile(name,transponderUuid) }
    override suspend fun updateTrackingBases(bases: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies) =
        execute { delegate.updateTrackingBases(bases) }
    override suspend fun profileStoreCommand(data:ByteArray)=execute {delegate.profileStoreCommand(data)}
    override suspend fun setNativeCwOptions(options: com.rtbishop.look4sat.core.domain.repository.NativeCwOptions) =
        execute { delegate.setNativeCwOptions(options) }
    override suspend fun handsetMacroCommand(data: ByteArray) = execute { delegate.handsetMacroCommand(data) }
    override suspend fun refreshNativeDiagnostics() = execute { delegate.refreshNativeDiagnostics() }
    override val isConnected: Boolean get() = delegate.isConnected
    override val supportsPttReadback get() = delegate.supportsPttReadback
    override suspend fun readPttState() = execute(operation = delegate::readPttState)
    override suspend fun connect() = actor.execute(CONNECT_TIMEOUT_MILLIS, delegate::connect)
    override suspend fun disconnect() = execute(operation = delegate::disconnect)
    override suspend fun setAudioForeground(enabled: Boolean) = execute { delegate.setAudioForeground(enabled) }
    override suspend fun setRadioMonitor(enabled: Boolean) = execute { delegate.setRadioMonitor(enabled) }
    override suspend fun recordingAction(action: com.rtbishop.look4sat.core.domain.model.RadioRecordingAction) = execute { delegate.recordingAction(action) }
    override suspend fun saveRecording(destinationUri: String) = execute { delegate.saveRecording(destinationUri) }
    override suspend fun setFrequency(frequencyHz: Long) = execute { delegate.setFrequency(frequencyHz) }
    override suspend fun setMode(mode: String) = execute { delegate.setMode(mode) }
    override suspend fun setDataMode(enabled: Boolean, baseMode: String) =
        execute { delegate.setDataMode(enabled, baseMode) }
    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String) =
        execute { delegate.setTxDataMode(enabled, baseMode) }
    override suspend fun setCtcssMode(enabled: Boolean) = execute { delegate.setCtcssMode(enabled) }
    override suspend fun setCtcssTone(toneHz: Double) = execute { delegate.setCtcssTone(toneHz) }
    override suspend fun readFrequencyAndMode() = execute(operation = delegate::readFrequencyAndMode)
    override suspend fun pttOn() = actor.executePttOn(operation = delegate::pttOn)
    override fun pttSafetyGeneration() = actor.currentPttGeneration()
    override suspend fun pttOnIfGeneration(expectedGeneration: Long) =
        actor.executePttOn(expectedGeneration, delegate::pttOn)
    override suspend fun pttOff() = actor.executePttOff(delegate::pttOff)
    override fun invalidatePendingPttOn() = actor.invalidatePendingPttOn()
    override suspend fun setBand(frequencyHz: Long) = execute { delegate.setBand(frequencyHz) }
    override suspend fun setVfo(vfoA: Boolean) = execute { delegate.setVfo(vfoA) }
    override suspend fun setSplitMode(enabled: Boolean) = execute { delegate.setSplitMode(enabled) }
    override suspend fun setSplitModes(rxMode: String?, txMode: String?) =
        execute { delegate.setSplitModes(rxMode, txMode) }
    override suspend fun configureTxCtcss(toneHz: Double?) =
        execute { delegate.configureTxCtcss(toneHz) }
    override suspend fun setWorkingFrequency(frequencyHz: Long) = execute { delegate.setWorkingFrequency(frequencyHz) }
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = execute { delegate.setTxVfoFrequency(frequencyHz) }
    override suspend fun readWorkingFrequency() = execute(operation = delegate::readWorkingFrequency)
    override suspend fun readTxVfoFrequency() = execute(operation = delegate::readTxVfoFrequency)

    private companion object {
        // Two 10 s RFCOMM attempts plus bounded read-only CI-V probes.
        const val CONNECT_TIMEOUT_MILLIS = 40_000L
    }
}
