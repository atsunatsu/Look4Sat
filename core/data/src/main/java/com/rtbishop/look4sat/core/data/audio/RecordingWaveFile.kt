// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.data.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun createRecordingFile(directory: File, startedMillis: Long, satellite: String, maxElevation: Double?): File {
    val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date(startedMillis))
    val name=satellite.replace(Regex("""[<>:"/\\|?*\p{Cntrl}]"""),"_").trim().take(80).ifBlank {"Satellite"}
    val angle=maxElevation?.takeIf {it.isFinite() && it in 0.0..90.0}
        ?.let {String.format(Locale.ROOT,"%.1fdeg",it)} ?: "unknown-deg"
    val stem="$stamp-$name-$angle"
    for(index in 0..9999) {
        val suffix=if(index==0) "" else " (${index+1})"
        if(File(directory,"$stem$suffix.wav").exists()) continue
        val partial=File(directory,"$stem$suffix.wav.partial")
        if(partial.createNewFile()) return partial
    }
    error("Could not allocate a recording filename")
}

internal fun recordingWaveHeader(bytes: Long,rate: Int): ByteArray {
    require(rate in 8000..192000 && bytes in 0..0xffffffffL-36 && bytes%2==0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt((bytes+36).toInt());put("WAVEfmt ".toByteArray());putInt(16)
        putShort(1);putShort(1);putInt(rate);putInt(rate*2);putShort(2);putShort(16)
        put("data".toByteArray());putInt(bytes.toInt())
    }.array()
}
internal class RecordingWaveFile(val file: File,val rate: Int): AutoCloseable {
    private val output=RandomAccessFile(file,"rw")
    var samples=0L;private set
    private var checkpoint=0L
    init {
        try {output.setLength(0);output.write(recordingWaveHeader(0,rate))}
        catch(error:Exception) {output.close();throw error}
    }
    fun write(pcm: FloatArray) {
        require(pcm.all {it.isFinite()}) {"Invalid received PCM"}
        val bytes=ByteBuffer.allocate(pcm.size*2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach {bytes.putShort((it.coerceIn(-1f,1f)*32768f).toInt().coerceIn(-32768,32767).toShort())}
        output.seek(44+samples*2);output.write(bytes.array());samples+=pcm.size
        if(samples-checkpoint>=rate*5L) {updateHeader();output.fd.sync();checkpoint=samples}
    }
    private fun updateHeader() {output.seek(0);output.write(recordingWaveHeader(samples*2,rate))}
    override fun close() {try {output.setLength(44+samples*2);updateHeader();output.fd.sync()} finally {output.close()}}
}
internal fun recoverRecording(file: File): File? = runCatching {
    if(file.length()<=44) {file.delete();return@runCatching null}
    RandomAccessFile(file,"rw").use {io ->
        val header=ByteArray(44);io.readFully(header)
        require(String(header,0,4)=="RIFF" && String(header,8,4)=="WAVE")
        val rate=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
        val bytes=(io.length()-44)/2*2;io.setLength(44+bytes);io.seek(0);io.write(recordingWaveHeader(bytes,rate));io.fd.sync()
    }
    val target=File(file.parentFile,file.name.removeSuffix(".partial"))
    if(!target.exists() && file.renameTo(target)) target else null
}.getOrNull()
