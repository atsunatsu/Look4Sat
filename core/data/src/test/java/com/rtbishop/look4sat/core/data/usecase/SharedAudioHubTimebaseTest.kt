/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.usecase

import android.media.AudioTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedAudioHubTimebaseTest {

    @Test
    fun `audio timestamp uses same boottime axis as disciplined clock`() {
        assertEquals(AudioTimestamp.TIMEBASE_BOOTTIME, AUDIO_TIMESTAMP_TIMEBASE)
    }
}
