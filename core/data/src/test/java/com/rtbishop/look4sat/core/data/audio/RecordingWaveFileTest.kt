package com.rtbishop.look4sat.core.data.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingWaveFileTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun readableNamesIncludePassMaximumAndNeverOverwriteExistingSegments() {
        val date=java.util.Calendar.getInstance().apply {set(2026,9,29,18,16,8);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
        val first=createRecordingFile(temp.root,date,"OSCAR-7 (AO-7)",87.8)
        assertEquals("20261029-181608-OSCAR-7 (AO-7)-87.8deg.wav.partial",first.name)
        first.writeText("original")
        assertEquals("20261029-181608-OSCAR-7 (AO-7)-87.8deg (2).wav.partial",createRecordingFile(temp.root,date,"OSCAR-7 (AO-7)",87.8).name)
        assertEquals("original",first.readText())
        assertTrue(first.renameTo(File(temp.root,first.name.removeSuffix(".partial"))))
        assertTrue(createRecordingFile(temp.root,date,"OSCAR-7 (AO-7)",87.8).name.contains(" (3).wav.partial"))
    }
    @Test fun namesPreserveUnicodeSanitizePathCharactersAndDoNotInventUnknownAngles() {
        val file=createRecordingFile(temp.root,0,"希望/测试:卫星",Double.NaN)
        assertTrue(file.name.endsWith("-希望_测试_卫星-unknown-deg.wav.partial"))
        assertEquals(temp.root.canonicalFile,file.parentFile.canonicalFile)
    }
    @Test fun monoHeaderAndSampleCountComeFromPcmRatherThanWallClock() {
        val file=File(temp.root,"test.wav.partial")
        RecordingWaveFile(file,48000).use {writer ->
            writer.write(floatArrayOf(-1f,0f,1f));assertEquals(3L,writer.samples)
        }
        val data=file.readBytes();val header=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(50,data.size);assertEquals(1,header.getShort(22).toInt());assertEquals(48000,header.getInt(24))
        assertEquals(6,header.getInt(40));assertEquals(-32768,header.getShort(44).toInt())
        assertEquals(32767,header.getShort(48).toInt())
    }
    @Test fun interruptedFileIsRepairedUsingActualBytesAndKeepsSampleRate() {
        val file=File(temp.root,"recovered.wav.partial")
        file.writeBytes(recordingWaveHeader(0,32000)+ByteArray(6400))
        val recovered=recoverRecording(file)!!
        assertEquals("recovered.wav",recovered.name)
        val header=ByteBuffer.wrap(recovered.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(6400,header.getInt(40));assertEquals(32000,header.getInt(24))
        assertFalse(file.exists())
    }
    @Test fun invalidSamplesAndUnsupportedRatesAreRejected() {
        assertTrue(runCatching {recordingWaveHeader(0,0)}.isFailure)
        RecordingWaveFile(File(temp.root,"invalid.wav.partial"),12000).use {writer ->
            assertTrue(runCatching {writer.write(floatArrayOf(Float.NaN))}.isFailure)
            assertEquals(0L,writer.samples)
        }
    }
}
