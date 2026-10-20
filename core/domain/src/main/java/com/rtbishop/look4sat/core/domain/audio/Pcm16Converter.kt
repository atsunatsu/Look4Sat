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

object Pcm16Converter {
    fun toFloat(input: ShortArray, count: Int = input.size): FloatArray {
        require(count in 0..input.size)
        return FloatArray(count).also { output ->
            toFloat(input, count, output)
        }
    }

    fun toFloat(input: ShortArray, count: Int, output: FloatArray) {
        require(count in 0..input.size)
        require(output.size >= count)
        for (index in 0 until count) {
            output[index] = input[index] / 32768.0f
        }
    }
}
