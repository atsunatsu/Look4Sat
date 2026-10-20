/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAudioPipelineTest {
    @Test
    fun pcm16ConversionCoversFullScale() {
        val converted = Pcm16Converter.toFloat(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE))
        assertArrayEquals(floatArrayOf(-1f, -1f / 32768f, 0f, 1f / 32768f, 32767f / 32768f), converted, 0f)
    }

    @Test
    fun supportedCaptureRatesProduceExactTwelveKhzLength() {
        for (sourceRate in intArrayOf(12_000, 24_000, 48_000)) {
            val input = FloatArray(sourceRate) { 0.25f }
            val resampler = StreamingAudioResampler(sourceRate, 12_000)
            val output = resampler.process(input) + resampler.finish()
            assertEquals(12_000, output.size)
            output.forEach { assertEquals(0.25f, it, 1e-4f) }
        }
    }

    @Test
    fun arbitraryChunkBoundariesMatchSingleChunkResult() {
        val input = tone(48_000, 1_300.0)
        val single = StreamingAudioResampler(48_000, 12_000).run {
            process(input) + finish()
        }
        val chunkedResampler = StreamingAudioResampler(48_000, 12_000)
        val chunks = ArrayList<Float>()
        var offset = 0
        val sizes = intArrayOf(1, 17, 509, 2_047, 113)
        var sizeIndex = 0
        while (offset < input.size) {
            val end = (offset + sizes[sizeIndex++ % sizes.size]).coerceAtMost(input.size)
            chunkedResampler.process(input.copyOfRange(offset, end)).forEach(chunks::add)
            offset = end
        }
        chunkedResampler.finish().forEach(chunks::add)
        val chunked = chunks.toFloatArray()
        assertArrayEquals(single, chunked, 1e-6f)
    }

    @Test
    fun passbandIsPreservedAndStopbandIsRejected() {
        for (sourceRate in intArrayOf(24_000, 48_000)) {
            val passband = resample(tone(sourceRate, 1_000.0), sourceRate, 12_000)
            assertTrue(toneAmplitude(passband, 12_000, 1_000.0) > 0.95)

            val stopFrequency = if (sourceRate == 24_000) 9_000.0 else 15_000.0
            val stopband = resample(tone(sourceRate, stopFrequency), sourceRate, 12_000)
            assertTrue(rms(stopband) < 0.05)
        }
    }

    @Test
    fun morseExpertBridgeRatesProduceEightKhz() {
        for (sourceRate in intArrayOf(12_000, 24_000, 48_000)) {
            val output = resample(tone(sourceRate, 700.0), sourceRate, 8_000)
            assertEquals(8_000, output.size)
            assertTrue(toneAmplitude(output, 8_000, 700.0) > 0.9)
        }
    }

    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        return StreamingAudioResampler(inputRate, outputRate).run { process(input) + finish() }
    }

    private fun tone(sampleRate: Int, frequency: Double): FloatArray = FloatArray(sampleRate) {
        sin(2.0 * PI * frequency * it / sampleRate).toFloat()
    }

    private fun toneAmplitude(samples: FloatArray, sampleRate: Int, frequency: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        val edge = 256.coerceAtMost(samples.size / 8)
        for (index in edge until samples.size - edge) {
            val phase = 2.0 * PI * frequency * index / sampleRate
            real += samples[index] * cos(phase)
            imaginary += samples[index] * sin(phase)
        }
        return 2.0 * hypot(real, imaginary) / (samples.size - edge * 2).coerceAtLeast(1)
    }

    private fun rms(samples: FloatArray): Double {
        val edge = 256.coerceAtMost(samples.size / 8)
        var power = 0.0
        for (index in edge until samples.size - edge) power += samples[index] * samples[index]
        return kotlin.math.sqrt(power / (samples.size - edge * 2).coerceAtLeast(1))
    }
}
