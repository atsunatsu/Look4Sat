// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.domain.audio

import kotlinx.coroutines.flow.StateFlow

enum class RecordingSourceKind { MICROPHONE, DEVICE, WLAN, N76 }
data class RecordingSource(val id: String, val name: String, val kind: RecordingSourceKind)
data class RecordingRequest(val sourceId: String, val satellite: String = "", val passAos: Long? = null, val maxElevation: Double? = null)
data class RecordingFile(val id: String, val name: String, val bytes: Long, val modified: Long)
enum class RecordingPhase { IDLE, PREPARING, RECORDING, FINISHING }
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE, val source: RecordingSource? = null,
    val durationMillis: Long = 0, val sampleRate: Int = 0, val fileName: String = "",
    val error: String? = null, val playing: Boolean = false, val exporting: Boolean = false
) { val active get()=phase!=RecordingPhase.IDLE }

interface IRecordingForegroundLauncher { fun startRecordingForeground(request: RecordingRequest) }
interface IRecordingService {
    val state: StateFlow<RecordingState>
    val files: StateFlow<List<RecordingFile>>
    fun sources(): List<RecordingSource>
    fun start(request: RecordingRequest)
    fun failStart(reason: String)
    /** Called only by the foreground service after its notification is posted. */
    suspend fun runRecording(request: RecordingRequest)
    suspend fun stop()
    suspend fun refreshFiles()
    suspend fun play(id: String)
    suspend fun stopPlayback()
    suspend fun export(id: String, destinationUri: String)
    suspend fun delete(id: String)
    fun setApplicationForeground(foreground: Boolean)
}
