/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import java.io.File
import java.io.IOException
import java.io.OutputStream
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface N76RecordingDestination {
    fun open(): OutputStream?
    fun size(): Long? = null
    fun removeIncomplete() = Unit
}

internal fun copyN76Recording(source: File, destination: () -> N76RecordingDestination): Long {
    require(source.isFile && source.length() >= 44) { "Recording file is unavailable" }
    val expected = source.length()
    val target = destination()
    try {
        val output = target.open() ?: throw IOException("Document provider returned no output stream")
        val copied = output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        if (copied != expected) throw IOException("Recording changed while saving: expected=$expected copied=$copied")
        target.size()?.takeIf { it >= 0 }?.let { actual ->
            if (actual != expected) throw IOException("Incomplete recording export: expected=$expected actual=$actual")
        }
        return copied
    } catch (error: Exception) {
        runCatching { target.removeIncomplete() }
        throw error
    }
}

internal fun lastLocalN76Recording(directory: File, saved: String?): String? {
    fun valid(file: File): Boolean = runCatching {
        if (!file.isFile || file.parentFile?.canonicalFile != directory.canonicalFile || file.length() < 44) return@runCatching false
        val header = file.inputStream().use { input -> ByteArray(44).also { if (input.read(it) != 44) return@runCatching false } }
        header.contentEquals(n76WaveHeader(file.length() - 44))
    }.getOrDefault(false)
    saved?.takeUnless { it.startsWith("content:") }?.let(::File)?.takeIf(::valid)?.let { return it.absolutePath }
    return directory.listFiles()?.filter { it.extension.equals("wav", true) && valid(it) }
        ?.maxByOrNull(File::lastModified)?.absolutePath
}

internal class N76RecordingExportQueue(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<N76RecordingState>,
    private val diagnostics: IControlDiagnostics
) {
    private val mutex = Mutex()
    private var pending = 0

    @Synchronized private fun changePending(delta: Int) {
        pending += delta
        state.update { it.copy(exporting = pending > 0) }
    }

    fun enqueue(file: File, description: String, destination: () -> N76RecordingDestination) {
        changePending(1)
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val copied = copyN76Recording(file, destination)
                    diagnostics.record(ControlDiagnosticSource.RADIO, "n76/recording/export", "Saved bytes=$copied $description")
                    state.update { if (it.lastFile == file.absolutePath) it.copy(exportError = null, exportedFile = file.absolutePath) else it }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    diagnostics.record(ControlDiagnosticSource.RADIO, "n76/recording/export-failed",
                        "${error.javaClass.simpleName}: ${error.message}; $description localBytes=${file.length()}", ControlDiagnosticSeverity.ERROR)
                    state.update { if (it.lastFile == file.absolutePath) it.copy(exportError = "Recording kept locally; choose Save recording to export") else it }
                }
            }
        }.invokeOnCompletion {
            changePending(-1)
        }
    }
}
