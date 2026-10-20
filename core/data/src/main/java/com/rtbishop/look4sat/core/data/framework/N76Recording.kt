/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

data class N76RecordingState(val recording: Boolean = false, val playing: Boolean = false,
    val lastFile: String? = null, val error: String? = null, val exportError: String? = null,
    val exporting: Boolean = false, val exportedFile: String? = null)

interface N76Recording {
    val state: StateFlow<N76RecordingState>
    suspend fun start(satelliteName: String, radioAudioAvailable: Boolean): Boolean
    suspend fun stop(): Boolean
    suspend fun playLast(): Boolean
    suspend fun stopPlayback(): Boolean
    fun feedRadio(pcm: ShortArray)
    suspend fun save(destinationUri: String): Boolean = false
}

internal class N76PcmRing(private val capacity: Int = 32_000) {
    init { require(capacity > 0) }
    private val data = ShortArray(capacity)
    private var start = 0
    private var count = 0
    @Synchronized fun offer(samples: ShortArray) {
        samples.forEach { sample ->
            if (count == capacity) { start = (start + 1) % capacity; count-- }
            data[(start + count) % capacity] = sample; count++
        }
    }
    @Synchronized fun read(size: Int): ShortArray {
        val result = ShortArray(size)
        repeat(minOf(size, count)) { result[it] = data[(start + it) % capacity] }
        val consumed = minOf(size, count)
        start = (start + consumed) % capacity; count -= consumed
        return result
    }
    @Synchronized fun clear() { start = 0; count = 0 }
}

internal fun n76StereoPcm(mic: ShortArray, radio: ShortArray, gainDb: Int): ByteArray {
    require(mic.size == radio.size)
    val gain = 10.0.pow(gainDb.coerceIn(0, 24) / 20.0)
    return ByteBuffer.allocate(mic.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        mic.indices.forEach { index ->
            putShort((mic[index] * gain).roundToInt().coerceIn(-32768, 32767).toShort())
            putShort(radio[index])
        }
    }.array()
}

internal fun n76WaveHeader(size: Long): ByteArray {
    require(size in 0..0xffffffffL - 36 && size % 4 == 0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((size + 36).toInt()); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(2); putInt(32_000); putInt(128_000)
        putShort(4); putShort(16); put("data".toByteArray()); putInt(size.toInt())
    }.array()
}

internal class N76WaveFile(file: File) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var size = 0L
    private var closed = false
    init {
        try { output.setLength(0); output.write(n76WaveHeader(0)) }
        catch (error: Exception) { output.close(); throw error }
    }
    fun write(bytes: ByteArray) {
        check(!closed)
        require(bytes.size % 4 == 0 && size + bytes.size <= 0xffffffffL - 36)
        output.write(bytes); size += bytes.size
    }
    override fun close() {
        if (closed) return
        closed = true
        try { output.seek(0); output.write(n76WaveHeader(size)); output.fd.sync() }
        finally { output.close() }
    }
}

internal class AndroidN76Recording(
    private val context: Context,
    private val io: N76AudioIo,
    private val scope: CoroutineScope,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val options: () -> N76ControlOptions
) : N76Recording {
    private val preferences = context.getSharedPreferences("n76-recordings", Context.MODE_PRIVATE)
    private val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "N76")
    private val mutableState = MutableStateFlow(N76RecordingState(lastFile = lastLocalN76Recording(directory, preferences.getString("last", null))))
    override val state: StateFlow<N76RecordingState> = mutableState
    private val mutex = Mutex()
    private val exporter = N76RecordingExportQueue(scope, mutableState, diagnostics)
    private val microphone = N76PcmRing()
    private val radio = N76PcmRing()
    private var micJob: Job? = null
    private var writerJob: Job? = null
    private var player: MediaPlayer? = null
    @Volatile private var recordRadio = false

    override suspend fun start(satelliteName: String, radioAudioAvailable: Boolean): Boolean = mutex.withLock {
        if (mutableState.value.recording) return@withLock true
        val settings = options().normalized()
        when {
            !settings.recordRadio && !settings.recordMicrophone -> return@withLock failed("Select a recording source in CAT settings")
            settings.recordRadio && !radioAudioAvailable -> return@withLock failed("Enable Bluetooth audio to record the radio")
            settings.recordMicrophone && !io.hasMicrophonePermission() -> return@withLock failed("Allow microphone access to record the phone")
        }
        micJob?.cancelAndJoin(); micJob = null
        writerJob?.join()
        stopPlayback()
        microphone.clear(); radio.clear(); recordRadio = settings.recordRadio
        if (!directory.exists() && !directory.mkdirs()) return@withLock failed("Cannot create the recording folder")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val name = satelliteName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val file = try { File.createTempFile("$stamp-$name-", ".wav", directory) }
        catch (error: Exception) { return@withLock failed(error.message ?: "Cannot create recording") }
        val output = try { N76WaveFile(file) }
        catch (error: Exception) { return@withLock failed(error.message ?: "Cannot create recording") }
        mutableState.update { it.copy(recording = true, error = null) }
        if (settings.recordMicrophone) micJob = scope.launch(Dispatchers.IO) {
            try {
                io.microphone().collect { microphone.offer(it) }
                failed("Phone microphone stream ended")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error.message ?: "Microphone recording failed") }
        }
        writerJob = scope.launch(Dispatchers.IO) {
            var size = 0L
            val started = System.nanoTime()
            var blocks = 0L
            try {
                while (isActive && mutableState.value.recording) {
                    val next = started + (++blocks * 20_000_000)
                    val wait = (next - System.nanoTime()) / 1_000_000
                    if (wait > 0) delay(wait)
                    if (size + 2560 > 0xffffffffL - 36) { failed("Recording reached the WAV size limit"); break }
                    output.write(n76StereoPcm(microphone.read(640), radio.read(640), settings.micGainDb))
                    size += 2560
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error.message ?: "Recording write failed") }
            finally {
                withContext(NonCancellable) {
                    micJob?.cancelAndJoin(); micJob = null
                    var finalized = false
                    try { output.close(); finalized = true }
                    catch (error: Exception) { failed(error.message ?: "Recording finalization failed") }
                    mutableState.update { it.copy(recording = false, lastFile = if (finalized) file.absolutePath else it.lastFile,
                        exportError = if (finalized) null else it.exportError, exportedFile = if (finalized) null else it.exportedFile) }
                    if (finalized) preferences.edit().putString("last", file.absolutePath).apply()
                    if (finalized && settings.outputFolderUri.isNotBlank()) scheduleExport(file, settings.outputFolderUri, tree = true)
                }
            }
        }
        true
    }

    override fun feedRadio(pcm: ShortArray) {
        if (mutableState.value.recording && recordRadio) radio.offer(pcm)
    }

    override suspend fun stop(): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            mutableState.update { it.copy(recording = false) }
            micJob?.cancelAndJoin(); micJob = null
            writerJob?.join(); writerJob = null
            recordRadio = false
            true
        }
    }

    override suspend fun playLast(): Boolean = withContext(Dispatchers.Main) {
        if (mutableState.value.recording) return@withContext false
        val path = mutableState.value.lastFile ?: return@withContext failed("No recording to play")
        stopPlayback()
        try {
            val candidate = MediaPlayer()
            player = candidate
            candidate.setDataSource(context, if (path.startsWith("content:")) Uri.parse(path) else Uri.fromFile(File(path)))
            candidate.setOnCompletionListener { if (player === it) scope.launch { stopPlayback() } }
            candidate.setOnErrorListener { current, _, _ ->
                if (player === current) { scope.launch { stopPlayback() }; failed("Recording playback failed") }
                true
            }
            candidate.setOnPreparedListener { if (player === it) { it.start(); mutableState.update { state -> state.copy(playing = true, error = null) } } }
            candidate.prepareAsync()
            true
        } catch (error: Exception) { stopPlayback(); failed(error.message ?: "Recording playback failed") }
    }

    override suspend fun stopPlayback(): Boolean = withContext(Dispatchers.Main) {
        val current = player; player = null
        runCatching { current?.stop() }
        runCatching { current?.release() }.onFailure {
            diagnostics.record(ControlDiagnosticSource.RADIO, "n76/recording/playback-close", "${it.javaClass.simpleName}: ${it.message}", ControlDiagnosticSeverity.ERROR)
        }
        mutableState.update { it.copy(playing = false) }
        true
    }

    private fun failed(message: String): Boolean {
        mutableState.update { it.copy(error = message, recording = false) }
        diagnostics.record(ControlDiagnosticSource.RADIO, "n76/recording/failed", message, ControlDiagnosticSeverity.ERROR)
        return false
    }

    override suspend fun save(destinationUri: String): Boolean {
        val file = mutableState.value.lastFile?.let(::File)?.takeIf { it.isFile }
            ?: return failed("No recording to save")
        if (mutableState.value.exporting) return true
        scheduleExport(file, destinationUri, tree = false)
        return true
    }

    private fun scheduleExport(file: File, location: String, tree: Boolean) {
        val description = "provider=${runCatching { Uri.parse(location).authority }.getOrNull()} tree=$tree"
        exporter.enqueue(file, description) {
            val uri = Uri.parse(location)
            require(uri.scheme == "content") { "Recording destination is not a document URI" }
            val destination = if (tree) {
                require(DocumentsContract.isTreeUri(uri)) { "Recording folder is not a document tree" }
                check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
                    "Recording folder write permission expired; reselect the folder"
                }
                val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                DocumentsContract.createDocument(context.contentResolver, parent, "audio/wav", file.name)
                    ?: error("Document provider could not create the recording")
            } else uri
            object : N76RecordingDestination {
                override fun open() = context.contentResolver.openOutputStream(destination, "w")
                override fun size(): Long? = runCatching {
                    context.contentResolver.query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
                    }
                }.getOrNull()
                override fun removeIncomplete() {
                    // Only a document created for this automatic copy is removed.
                    if (tree) DocumentsContract.deleteDocument(context.contentResolver, destination)
                }
            }
        }
    }
}
