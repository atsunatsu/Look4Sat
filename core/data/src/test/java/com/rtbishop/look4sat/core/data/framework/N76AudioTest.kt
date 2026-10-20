package com.rtbishop.look4sat.core.data.framework

import java.util.Collections
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class N76AudioTest {
    @Test fun codecInteroperatesWithIndependentBluezLibsbcInBothDirections() {
        val executable = System.getenv("N76_SBC_ORACLE_PATH")
        assumeTrue("Optional local libsbc oracle is not configured", !executable.isNullOrBlank())
        fun oracle(operation: String, bytes: ByteArray): ByteArray {
            val process = ProcessBuilder(executable, operation).start()
            process.outputStream.bufferedWriter().use { writer -> writer.write(bytes.joinToString("") { "%02x".format(it.toInt() and 255) }) }
            val response = process.inputStream.bufferedReader().readText().trim()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return response.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        fun pcm(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
            ShortArray(bytes.size / 2) { buffer.short }
        }
        fun decode(bytes: ByteArray): ShortArray {
            val decoder = N76SbcCodec()
            return (0 until bytes.size step 44).flatMap { decoder.decode(bytes, it)!!.toList() }.toShortArray()
        }
        val source = ShortArray(128 * 32) { (sin(2 * PI * 1000 * it / 32000) * 6000).toInt().toShort() }
        val encoder = N76SbcCodec()
        val encoded = (0 until 32).flatMap { encoder.encode(source, it * 128).toList() }.toByteArray()
        val nativePcm = pcm(oracle("d", encoded))
        assertEquals(source.size, nativePcm.size)
        val kotlinPcm = decode(encoded)
        val error = (512 until nativePcm.size).sumOf { i ->
            val difference = nativePcm[i].toDouble() - kotlinPcm[i]; difference * difference
        } / (nativePcm.size - 512)
        assertTrue("Kotlin/libsbc decoder mismatch=$error", error < 100_000)
        val raw = ByteBuffer.allocate(source.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { source.forEach(::putShort) }.array()
        val nativeEncoded = oracle("e", raw)
        assertEquals(44 * 32, nativeEncoded.size)
        val reverse = decode(nativeEncoded)
        val reverseReference = pcm(oracle("d", nativeEncoded))
        assertEquals(source.size, reverse.size)
        val reverseError = (512 until reverse.size).sumOf { i ->
            val difference = reverseReference[i].toDouble() - reverse[i]; difference * difference
        } / (reverse.size - 512)
        assertTrue("libsbc/Kotlin decoder mismatch=$reverseError", reverseError < 100_000)
    }

    @Test fun framingEscapesFragmentsAndBoundsCorruptInput() {
        val bytes = byteArrayOf(0x9c.toByte(), 0x7d, 0x7e, 0)
        val wire = N76AudioProtocol.audio(bytes)
        val decoder = N76AudioProtocol.Decoder()
        assertTrue(decoder.feed(wire.copyOfRange(0, 4)).isEmpty())
        assertArrayEquals(byteArrayOf(0) + bytes, decoder.feed(wire.copyOfRange(4, wire.size)).single())
        assertEquals(2, decoder.feed(wire + wire).size)
        decoder.feed(byteArrayOf(0x7e) + ByteArray(9000))
        assertEquals(1, decoder.feed(wire).size)
        assertTrue(decoder.feed(byteArrayOf(0x7e, 0x7d, 0x7e)).isEmpty())
        assertEquals(11, N76AudioProtocol.stop.size)
    }

    @Test fun codecRoundTripsSpeechBandToneWithBoundedErrorAndRejectsCorruption() {
        val encoder = N76SbcCodec()
        val decoder = N76SbcCodec()
        val input = ShortArray(128 * 64) { (sin(2 * PI * 1000 * it / 32000) * 8000).toInt().toShort() }
        val decoded = mutableListOf<Short>()
        repeat(64) { frame ->
            val sbc = encoder.encode(input, frame * 128)
            assertEquals(44, sbc.size)
            assertEquals(44, decoder.frameLength(sbc))
            decoded += decoder.decode(sbc)!!.toList()
            val broken = sbc.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
            assertNull(decoder.decode(broken))
        }
        // The analysis/synthesis filter bank has a fixed delay; compare after warm-up.
        val bestError = (0..160).minOf { shift ->
            (1024 until input.size - 160).sumOf { i ->
                val difference = decoded[i + shift].toDouble() - input[i]
                difference * difference
            } / (input.size - 1184)
        }
        assertTrue("SBC voice reconstruction error=$bestError", bestError < 3_000_000)
        assertNull(decoder.decode(byteArrayOf(0x9c.toByte(), 0x71, 18)))
        assertNull(decoder.frameLength(byteArrayOf(0x9c.toByte(), 0x75, 18, 0)))
        assertNull(decoder.frameLength(byteArrayOf(0x9c.toByte(), 0x71, 0, 0)))
    }

    @Test fun openingAudioNeverCapturesMicrophoneOrTransmitsAndDecodesReceivedSbc() = withSession { session, wire, io ->
        assertEquals(0, io.captures)
        assertTrue(wire.writes.isEmpty())
        val frame = N76AudioProtocol.audio(N76SbcCodec().encode(ShortArray(128)))
        wire.receive(frame)
        delay(100)
        assertTrue(io.played.isNotEmpty())
        assertEquals(0, io.captures)
        assertFalse(session.state.value.transmitting)
    }

    @Test fun microphoneDeniedNeverSendsRfAndReleaseCannotBeFollowedByStaleAudio() = withSession { session, wire, io ->
        io.permission = false
        assertFalse(session.startTransmit())
        assertTrue(wire.writes.isEmpty())
        io.permission = true
        assertTrue(session.startTransmit())
        delay(30)
        assertTrue(session.stopTransmit())
        val count = wire.writes.size
        delay(60)
        assertEquals(count, wire.writes.size)
        assertArrayEquals(N76AudioProtocol.stop, wire.writes.last())
        assertFalse(session.state.value.transmitting)
    }

    @Test fun audioDisconnectStopsAnOwnedTransmissionAndReleasesPlayback() = withSession { session, wire, io ->
        assertTrue(session.startTransmit())
        session.disconnect()
        assertFalse(session.state.value.connected)
        assertFalse(session.state.value.transmitting)
        assertTrue(io.closed)
        assertFalse(wire.isConnected)
        assertArrayEquals(N76AudioProtocol.stop, wire.writes.last())
    }

    @Test fun audioReconnectAfterTransportLossDoesNotResumeTransmission() = withSession { session, wire, io ->
        assertTrue(session.startTransmit())
        wire.disconnect()
        delay(80)
        assertFalse(session.state.value.connected)
        assertTrue(session.connect())
        assertTrue(session.state.value.connected)
        assertFalse(session.state.value.transmitting)
        val captures = io.captures
        delay(30)
        assertEquals(captures, io.captures)
    }

    private fun withSession(block: suspend (N76AudioSession, Wire, Io) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wire = Wire(); val io = Io()
        val session = N76AudioSession(wire, io, scope)
        try { assertTrue(session.connect()); assertTrue(wire.secureFirst); block(session, wire, io) }
        finally { session.disconnect(); scope.cancel() }
    }
    private class Io : N76AudioIo {
        var permission = true
        @Volatile var captures = 0
        var closed = false
        val played = Collections.synchronizedList(mutableListOf<ShortArray>())
        override fun openPlayback() = true
        override fun play(pcm: ShortArray) { played += pcm }
        override fun closePlayback() { closed = true }
        override fun hasMicrophonePermission() = permission
        override fun microphone(): Flow<ShortArray> = flow {
            captures++
            while (true) { emit(ShortArray(128)); delay(4) }
        }
    }
    private class Wire : RadioTransport {
        var secureFirst = false
        override fun configureBluetoothConnection(secureFirst: Boolean, timeoutMillis: Long) { this.secureFirst = secureFirst }
        @Volatile override var isConnected = false
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        private var input = byteArrayOf()
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun disconnect() { isConnected = false }
        override suspend fun write(bytes: ByteArray): Boolean {
            if (!isConnected) return false
            writes += bytes.copyOf(); return true
        }
        @Synchronized fun receive(bytes: ByteArray) { input += bytes }
        override suspend fun readAvailable(maxBytes: Int): ByteArray = synchronized(this) {
            val count = minOf(maxBytes, input.size)
            val result = input.copyOfRange(0, count)
            input = input.copyOfRange(count, input.size)
            result
        }
    }
}
