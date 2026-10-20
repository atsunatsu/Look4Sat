package com.rtbishop.look4sat.core.data.framework

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class N76RecordingTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun stoppingARecordingFinalizesTheActualFileForPlayback() {
        val file = temporary.newFile("test.wav")
        val samples = n76StereoPcm(shortArrayOf(10, 20), shortArrayOf(30, 40), 0)
        val writer = N76WaveFile(file)
        writer.write(samples); writer.write(samples); writer.close(); writer.close()
        val bytes = file.readBytes()
        assertEquals(60, bytes.size)
        assertEquals(16, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertArrayEquals(samples + samples, bytes.copyOfRange(44, 60))
    }
    @Test fun boundedPcmQueueDropsOldestAndPadsOnlyMissingSamples() {
        val queue = N76PcmRing(4)
        queue.offer(shortArrayOf(1, 2, 3)); queue.offer(shortArrayOf(4, 5, 6))
        assertArrayEquals(shortArrayOf(3, 4, 5), queue.read(3))
        assertArrayEquals(shortArrayOf(6, 0, 0), queue.read(3))
        queue.offer(shortArrayOf(7, 8)); queue.clear()
        assertArrayEquals(shortArrayOf(0, 0), queue.read(2))
    }
    @Test fun stereoLayoutKeepsPhoneOnLeftRadioOnRightAndGainClipsWithoutWrapping() {
        val bytes = n76StereoPcm(shortArrayOf(100, -30000), shortArrayOf(200, -100), 24)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1585, data.short.toInt())
        assertEquals(200, data.short.toInt())
        assertEquals(-32768, data.short.toInt())
        assertEquals(-100, data.short.toInt())
    }
    @Test fun waveHeaderUsesStereo32Khz16BitPcmAndFinalDataLength() {
        val bytes = n76WaveHeader(2560)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2596, data.getInt(4))
        assertEquals(2, data.getShort(22).toInt())
        assertEquals(32000, data.getInt(24))
        assertEquals(128000, data.getInt(28))
        assertEquals(2560, data.getInt(40))
    }
}
