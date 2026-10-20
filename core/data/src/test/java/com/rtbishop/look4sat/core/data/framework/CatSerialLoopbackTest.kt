package com.rtbishop.look4sat.core.data.framework

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runs production controllers through real loopback sockets; never opens radio hardware. */
class CatSerialLoopbackTest {
    @Test fun legacyYaesuAndIcomModelsRoundTripFragmentedSerialFrames() = runBlocking {
        val variants = listOf("FT817","FT857","IC9700","IC910","IC820")
        for (model in variants) {
            ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
                val error=AtomicReference<Throwable?>()
                val worker=thread(isDaemon=true,name="CAT-$model-simulator") {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout=5_000
                            val input=socket.getInputStream(); val output=socket.getOutputStream()
                            var frequency=145_500_000L; var mode=1; var ptt=0
                            val yaesu=model.startsWith("FT")
                            val address=when(model) { "IC9700"->0xa2; "IC910"->0x60; else->0x42 }
                            while (true) {
                                val first=input.read(); if(first<0) break
                                val frame=ArrayList<Int>().apply { add(first) }
                                if (yaesu) repeat(4) { frame.add(input.read().also { check(it>=0) }) }
                                else while(frame.last()!=0xfd) { check(frame.size<64); frame.add(input.read().also { check(it>=0) }) }
                                val bytes=frame.map(Int::toByte).toByteArray()
                                val response=if(yaesu) {
                                    when(frame.last()) {
                                        0x03 -> Ft817CatProtocol.encodeFrequencyBcd(frequency)+byteArrayOf(mode.toByte())
                                        0x01 -> { frequency=Ft817CatProtocol.decodeFrequencyBcd(bytes.copyOfRange(0,4)); byteArrayOf(0) }
                                        0x07 -> { mode=frame[0]; byteArrayOf(0) }
                                        0xf7 -> byteArrayOf(0xff.toByte())
                                        0x88,0x0a,0x0b -> byteArrayOf(0)
                                        else -> error("Unexpected Yaesu opcode ${frame.last()}")
                                    }
                                } else {
                                    check(frame[2]==address); check(frame[3]==0xe0)
                                    val payload=when(frame[4]) {
                                        0x07 -> byteArrayOf(0xfb.toByte())
                                        0x03 -> byteArrayOf(0x03)+IcomCivProtocol.encodeFrequencyBcd(frequency)
                                        0x04 -> byteArrayOf(0x04,mode.toByte())
                                        0x05 -> { frequency=IcomCivProtocol.decodeFrequencyBcd(bytes.copyOfRange(5,10)); byteArrayOf(0xfb.toByte()) }
                                        0x06 -> { mode=frame[5]; byteArrayOf(0xfb.toByte()) }
                                        0x1c -> if(frame.size==7) byteArrayOf(0x1c,0,ptt.toByte()) else {
                                            ptt=frame[6]; byteArrayOf(0xfb.toByte())
                                        }
                                        else -> error("Unexpected CI-V opcode ${frame[4]}")
                                    }
                                    // CI-V echo must not be treated as a controller reply.
                                    output.write(bytes); output.flush()
                                    byteArrayOf(0xfe.toByte(),0xfe.toByte(),0xe0.toByte(),address.toByte())+payload+byteArrayOf(0xfd.toByte())
                                }
                                val split=response.size/2
                                output.write(response,0,split); output.flush(); Thread.sleep(8)
                                output.write(response,split,response.size-split); output.flush()
                            }
                        }
                    } catch (failure:Throwable) { error.set(failure) }
                }
                val link=TcpRadioTransport("127.0.0.1",server.localPort)
                val radio=when(model) {
                    "FT817" -> Ft817Controller(null,"loopback",link,YaesuCatVariant.FT817)
                    "FT857" -> Ft817Controller(null,"loopback",link,YaesuCatVariant.FT857)
                    else -> {
                        val variant=IcomCivVariant.valueOf(model)
                        val address=when(variant) { IcomCivVariant.IC9700->0xa2; IcomCivVariant.IC910->0x60; else->0x42 }
                        Ic705Controller(null,"loopback",address.toByte(),link,variant,false)
                    }
                }
                try {
                    assertTrue(model,radio.connect())
                    assertTrue(model,radio.setFrequency(145_925_000))
                    assertTrue(model,radio.setMode("FM"))
                    assertEquals(model,145_925_000L to "FM",radio.readFrequencyAndMode())
                    assertTrue(radio.isConnected)
                } finally { radio.disconnect() }
                worker.join(6_000)
                assertFalse("$model simulator still running",worker.isAlive)
                assertNull(error.get()?.stackTraceToString(),error.get())
            }
        }
    }
}
