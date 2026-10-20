package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class N76RecordingExportTest {
    @Test fun completeCopyClosesTheProviderAndKeepsTheOriginal() = withRecording { _, source ->
        val target = Target()
        val original = source.readBytes()
        assertEquals(source.length(), copyN76Recording(source) { target })
        assertArrayEquals(original, target.bytes.toByteArray())
        assertTrue(target.closed)
        assertFalse(target.removed)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun permissionNullStreamWriteCloseAndTruncationFailuresKeepTheOriginal() = withRecording { _, source ->
        val original = source.readBytes()
        for (failure in listOf("permission", "null", "write", "close", "short")) {
            val target = Target(failure)
            assertTrue("$failure must fail", runCatching { copyN76Recording(source) { target } }.isFailure)
            assertTrue("$failure must clean the incomplete destination", target.removed)
            assertArrayEquals(original, source.readBytes())
        }
        assertTrue(runCatching { copyN76Recording(source) { throw SecurityException("Folder grant expired") } }.isFailure)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun retryClearsOnlyExportErrorAndNeverReplacesTheLocalPlaybackFile() = withRecording { _, source ->
        val state = MutableStateFlow(N76RecordingState(lastFile = source.absolutePath, error = "Microphone failed"))
        val diagnostics = ControlDiagnosticsBuffer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = N76RecordingExportQueue(scope, state, diagnostics)
            queue.enqueue(source, "provider=fake tree=true") { Target("permission") }
            withTimeout(3_000) { state.first { !it.exporting } }
            assertNotNull(state.value.exportError)
            assertEquals("Microphone failed", state.value.error)
            assertEquals(source.absolutePath, state.value.lastFile)
            assertTrue(diagnostics.events.value.any { it.stage == "n76/recording/export-failed" && "SecurityException" in it.message })
            queue.enqueue(source, "provider=new tree=false") { Target() }
            withTimeout(3_000) { state.first { !it.exporting } }
            assertNull(state.value.exportError)
            assertEquals("Microphone failed", state.value.error)
            assertEquals(source.absolutePath, state.value.lastFile)
            assertEquals(source.absolutePath, state.value.exportedFile)
            assertTrue(source.isFile)
        } finally { scope.cancel() }
    }

    @Test fun slowOldExportDoesNotBlockCallerOrReplaceANewerRecording() = withRecording { directory, source ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = MutableStateFlow(N76RecordingState(lastFile = source.absolutePath))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            N76RecordingExportQueue(scope, state, ControlDiagnosticsBuffer()).enqueue(source, "slow provider") {
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                Target()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(state.value.exporting)
            val newer = File(directory, "newer.wav").absolutePath
            state.value = state.value.copy(lastFile = newer)
            release.countDown()
            withTimeout(3_000) { state.first { !it.exporting } }
            assertEquals(newer, state.value.lastFile)
            assertNull(state.value.exportedFile)
            assertNull(state.value.exportError)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun legacyDocumentUriRecoversTheLastCompleteLocalWaveAndSkipsPartialFiles() = withRecording { directory, source ->
        source.setLastModified(1_000)
        val newer = File(directory, "newer.wav")
        N76WaveFile(newer).use { it.write(ByteArray(2560)) }
        newer.setLastModified(2_000)
        File(directory, "partial.wav").apply { writeBytes(ByteArray(300)); setLastModified(3_000) }
        assertEquals(newer.absolutePath, lastLocalN76Recording(directory, "content://expired-provider/last.wav"))
        assertEquals(source.absolutePath, lastLocalN76Recording(directory, source.absolutePath))
    }

    private fun withRecording(block: suspend (File, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("look4sat-n76-export-").toFile()
        try {
            val source = File(directory, "source.wav")
            N76WaveFile(source).use { it.write(ByteArray(2560) { index -> index.toByte() }) }
            block(directory, source)
        } finally { directory.deleteRecursively() }
    }

    private class Target(private val failure: String? = null) : N76RecordingDestination {
        var closed = false
        var removed = false
        val bytes = object : ByteArrayOutputStream() {
            override fun close() { closed = true; if (failure == "close") throw IOException("Provider close failed") }
        }
        override fun open(): OutputStream? = when (failure) {
            "permission" -> throw SecurityException("Folder grant expired")
            "null" -> null
            "write" -> object : OutputStream() { override fun write(value: Int) { throw IOException("Provider write failed") } }
            else -> bytes
        }
        override fun size(): Long = if (failure == "short") 1 else bytes.size().toLong()
        override fun removeIncomplete() { removed = true }
    }
}
