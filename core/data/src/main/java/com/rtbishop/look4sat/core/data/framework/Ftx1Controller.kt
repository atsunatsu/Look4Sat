/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Ftx1Controller(
    private val transport: RadioTransport,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val replyTimeoutMillis: Long = 1_000L
) : IRadioController {
    private val mutex=Mutex()
    private val decoder=Ftx1CatProtocol.Decoder()
    @Volatile private var connected=false
    private var selectedSide=0
    private var split=false
    override val isConnected get()=connected && transport.isConnected
    override val commandTimeoutMillis get()=12_000L
    override val supportsPttReadback get()=true

    override suspend fun connect(): Boolean = try { io(false) {
        if (isConnected) return@io true
        connected=transport.connect(); selectedSide=0; split=false; decoder.clear()
        if (!isConnected) { report("connect/transport",transport.lastIoFailure ?: "USB open failed"); return@io false }
        // No writes to frequency, mode, power, VFO or TX during identification.
        var ready=false
        for(attempt in 1..3) {
            if(query("ID;","ID") == "ID0840;" && readFrequency(0)!=null) { ready=true; break }
            if(!isConnected) break
            delay(100L)
        }
        if(ready) {
            val version=query("VE0;","VE0")
            val number=version?.takeIf { it.matches(Regex("VE0[0-9]{4};")) }?.substring(3,7)?.toIntOrNull()
            if(number!=null && number<108) { report("connect/firmware","FTX-1 MAIN firmware 1.08 or later is required"); ready=false }
        }
        if(!ready) { transport.disconnect(); connected=false; report("connect/failed","FTX-1 read-only handshake failed") }
        else diagnostics.record(ControlDiagnosticSource.RADIO,"ftx1/connect/ready","ID0840; USB CAT verified")
        ready
    } } catch(cancelled: CancellationException) {
        withContext(NonCancellable) { mutex.withLock {
            runCatching { transport.disconnect() }; connected=false; decoder.clear()
        } }
        throw cancelled
    }

    override suspend fun disconnect() = withContext(NonCancellable) {
        if(isConnected) withTimeoutOrNull(2_000L) { pttOff() }
        mutex.withLock {
            try { transport.disconnect() } finally {
                connected=false; split=false; selectedSide=0; decoder.clear()
                diagnostics.record(ControlDiagnosticSource.RADIO,"ftx1/disconnect","origin=app")
            }
        }
    }

    override suspend fun setFrequency(frequencyHz: Long) = io(false) { setFrequencyLocked(selectedSide,frequencyHz) }
    override suspend fun setWorkingFrequency(frequencyHz: Long) = io(false) { setFrequencyLocked(0,frequencyHz) }
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = io(false) { setFrequencyLocked(1,frequencyHz) }
    override suspend fun readWorkingFrequency(): Long? = io(null) { readFrequency(0) }
    override suspend fun readTxVfoFrequency(): Long? = io(null) { readFrequency(1) }
    override suspend fun setBand(frequencyHz: Long) = frequencyHz in Ftx1CatProtocol.MIN_HZ..Ftx1CatProtocol.MAX_HZ
    override suspend fun setMode(mode: String) = io(false) { setModeLocked(selectedSide,mode) }
    override suspend fun readFrequencyAndMode(): Pair<Long,String>? = io(null) {
        val frequency=readFrequency(selectedSide) ?: return@io null
        val mode=query("MD$selectedSide;","MD$selectedSide")?.let { Ftx1CatProtocol.parseMode(it,selectedSide) } ?: return@io null
        frequency to mode
    }
    override suspend fun setVfo(vfoA: Boolean) = io(false) {
        val side=if(vfoA) 0 else 1
        verify("VS$side;","VS;","VS","VS$side;").also { if(it) selectedSide=side }
    }
    override suspend fun setSplitMode(enabled: Boolean) = io(false) {
        // Hamlib's FTX-1 backend routes TX with FT, avoiding ST1's SUB tuning lock.
        val side=if(enabled) 1 else 0
        val applied=verify("ST0;","ST;","ST","ST0;") && verify("FT$side;","FT;","FT","FT$side;")
        if(applied) split=enabled
        applied
    }
    override suspend fun setSplitModes(rxMode: String?, txMode: String?) = io(false) {
        if(rxMode!=null && Ftx1CatProtocol.mode(0,rxMode)==null || txMode!=null && Ftx1CatProtocol.mode(1,txMode)==null) return@io false
        (rxMode==null || setModeLocked(0,rxMode)) && (txMode==null || setModeLocked(1,txMode))
    }
    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        val mode=Ftx1CatProtocol.dataMode(enabled,baseMode) ?: return false
        return io(false) { setModeLocked(0,mode) }
    }
    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean {
        val mode=Ftx1CatProtocol.dataMode(enabled,baseMode) ?: return false
        return io(false) { setModeLocked(if(split) 1 else selectedSide,mode) }
    }
    override suspend fun setCtcssMode(enabled: Boolean) = io(false) { setToneMode(selectedSide,enabled) }
    override suspend fun setCtcssTone(toneHz: Double) = io(false) { setTone(selectedSide,toneHz) }
    override suspend fun configureTxCtcss(toneHz: Double?) = io(false) {
        val side=if(split) 1 else selectedSide
        (toneHz==null || setTone(side,toneHz)) && setToneMode(side,toneHz!=null)
    }
    override suspend fun readPttState(): Boolean? = io(null) {
        when(query("TX;","TX")) { "TX0;"->false; "TX1;","TX2;"->true; else->null }
    }
    override suspend fun pttOn() = io(false) { verify("TX1;","TX;","TX","TX1;") }
    override suspend fun pttOff() = io(false) { verify("TX0;","TX;","TX","TX0;") }

    private suspend fun setFrequencyLocked(side: Int, hz: Long): Boolean {
        val command=Ftx1CatProtocol.frequency(side,hz) ?: return false
        if(!ensureVfoMode(side)) return false
        val prefix=if(side==0) "FA" else "FB"
        return verify(command,"$prefix;",prefix,command)
    }
    private suspend fun ensureVfoMode(side: Int): Boolean {
        val value=query("VM$side;","VM$side") ?: return false
        return value=="VM${side}00;" || verify("VM${side}00;","VM$side;","VM$side","VM${side}00;")
    }
    private suspend fun setModeLocked(side: Int, mode: String): Boolean {
        val command=Ftx1CatProtocol.mode(side,mode) ?: return false
        return ensureVfoMode(side) && verify(command,"MD$side;","MD$side",command)
    }
    private suspend fun setToneMode(side: Int, enabled: Boolean): Boolean {
        val command="CT$side${if(enabled) 1 else 0};"
        return verify(command,"CT$side;","CT$side",command)
    }
    private suspend fun setTone(side: Int, toneHz: Double): Boolean {
        val command=Ftx1CatProtocol.tone(side,toneHz) ?: return false
        return verify(command,"CN${side}0;","CN${side}0",command)
    }
    private suspend fun readFrequency(side: Int): Long? =
        query(if(side==0) "FA;" else "FB;",if(side==0) "FA" else "FB")?.let { Ftx1CatProtocol.parseFrequency(it,side) }

    private suspend fun verify(set: String, read: String, prefix: String, expected: String): Boolean {
        if(!isConnected) return false
        discardInput()
        write(set)
        // SET has no ACK. Reject an immediate error and remove any adapter echo before READ.
        delay(40L)
        val early=decoder.feed(transport.readAvailable(512))
        if(early.any { it=="?;" || it=="E;" }) { report("reply/rejected",set); return false }
        val actual=query(read,prefix)
        if(actual!=expected) { report("reply/mismatch","$set readback=${actual ?: "missing"}"); return false }
        return true
    }
    private suspend fun query(command: String, prefix: String): String? {
        if(!isConnected) return null
        discardInput()
        write(command)
        val deadline=System.nanoTime()+replyTimeoutMillis*1_000_000L
        while(System.nanoTime()<deadline && isConnected) {
            for(frame in decoder.feed(transport.readAvailable(512))) {
                if(frame=="?;" || frame=="E;") { report("reply/rejected",command); return null }
                if(frame.startsWith(prefix) && frame!=command) return frame
            }
            delay(5L)
        }
        report(if(isConnected) "reply/timeout" else "reply/disconnected",command)
        return null
    }
    private suspend fun discardInput() {
        decoder.clear()
        repeat(8) { if(transport.readAvailable(512).isEmpty()) return }
        throw IOException("FTX-1 input did not become quiet")
    }
    private suspend fun write(command: String) {
        if(!transport.write(command.toByteArray(Charsets.US_ASCII))) throw IOException(transport.lastIoFailure ?: "USB write failed")
    }
    private suspend fun <T> io(fallback: T, block: suspend ()->T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                connected=false
                withContext(NonCancellable) { runCatching { transport.disconnect() } }
                report("io/failed","${error.javaClass.simpleName}: ${error.message}")
                fallback
            }
        }
    }
    private fun report(stage: String, message: String) =
        diagnostics.record(ControlDiagnosticSource.RADIO,"ftx1/$stage",message,ControlDiagnosticSeverity.ERROR)
}
