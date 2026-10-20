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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 有状态单声道带限重采样器。固定的 Hamming-windowed sinc 核会抑制降采样混叠，
 * 绝对输入/输出位置跨任意 chunk 边界连续。
 */
class StreamingAudioResampler(
    val inputRate: Int,
    val outputRate: Int,
    private val radius: Int = DEFAULT_RADIUS
) {
    private val ring = FloatArray((radius * 4 + 32).coerceAtLeast(256))
    private val inputPerOutput = inputRate.toDouble() / outputRate
    private val cutoff = 0.45 * minOf(1.0, outputRate.toDouble() / inputRate)
    private var received = 0L
    private var nextOutput = 0L
    private var firstSample = 0f
    private var lastSample = 0f
    private var finished = false

    init {
        require(inputRate > 0 && outputRate > 0)
        require(radius >= 8)
    }

    fun process(input: FloatArray): FloatArray {
        check(!finished) { "重采样器已经结束" }
        if (input.isEmpty()) return FloatArray(0)
        if (inputRate == outputRate) {
            received += input.size
            nextOutput += input.size
            return input.copyOf()
        }

        val estimated = ceil(input.size.toDouble() * outputRate / inputRate).toInt() + 2
        var output = FloatArray(estimated)
        var written = 0
        input.forEach { sample ->
            if (received == 0L) firstSample = sample
            lastSample = sample
            ring[(received % ring.size).toInt()] = sample
            received++

            while (isOutputReady()) {
                if (written == output.size) output = output.copyOf(output.size * 2)
                output[written++] = computeOutput(nextOutput++, finishing = false)
            }
        }
        return output.copyOf(written)
    }

    /** 用最后一个输入样本延拓滤波器尾部，并返回尚未产生的输出。 */
    fun finish(): FloatArray {
        if (finished || received == 0L || inputRate == outputRate) {
            finished = true
            return FloatArray(0)
        }
        val totalOutputs = received * outputRate / inputRate
        val output = FloatArray((totalOutputs - nextOutput).toInt())
        for (index in output.indices) {
            output[index] = computeOutput(nextOutput++, finishing = true)
        }
        finished = true
        return output
    }

    fun reset() {
        ring.fill(0f)
        received = 0L
        nextOutput = 0L
        firstSample = 0f
        lastSample = 0f
        finished = false
    }

    private fun isOutputReady(): Boolean {
        val center = nextOutput * inputPerOutput
        return floor(center).toLong() + radius < received
    }

    private fun computeOutput(outputIndex: Long, finishing: Boolean): Float {
        val center = outputIndex * inputPerOutput
        val centerFloor = floor(center).toLong()
        var weighted = 0.0
        var weightSum = 0.0
        for (offset in -radius..radius) {
            val sourceIndex = centerFloor + offset
            val distance = sourceIndex - center
            val sinc = if (distance == 0.0) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * distance) / (PI * distance)
            }
            val windowPosition = (offset + radius).toDouble() / (radius * 2)
            val window = 0.54 - 0.46 * cos(2.0 * PI * windowPosition)
            val weight = sinc * window
            weighted += sampleAt(sourceIndex, finishing) * weight
            weightSum += weight
        }
        return if (weightSum == 0.0) 0f else (weighted / weightSum).toFloat()
    }

    private fun sampleAt(index: Long, finishing: Boolean): Float {
        if (index < 0L) return firstSample
        if (finishing && index >= received) return lastSample
        check(index < received) { "重采样器读取了尚未到达的样本" }
        check(received - index <= ring.size) { "重采样器环形缓冲容量不足" }
        return ring[(index % ring.size).toInt()]
    }

    companion object {
        private const val DEFAULT_RADIUS = 32
    }
}
