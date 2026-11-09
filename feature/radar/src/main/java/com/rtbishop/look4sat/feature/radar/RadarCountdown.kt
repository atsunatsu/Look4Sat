/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.utility.toTimerString

internal enum class RadarPassPhase { UPCOMING, IN_PROGRESS, ENDED, DEEP_SPACE }

internal data class RadarCountdown(val time: String, val phase: RadarPassPhase) {
    val isAos: Boolean get() = phase == RadarPassPhase.UPCOMING
    val isEnded: Boolean get() = phase == RadarPassPhase.ENDED
}

/** One clock for the AOS/LOS header, countdown and pass-state decision. */
internal fun radarCountdown(
    isDeepSpace: Boolean, aosTime: Long, losTime: Long, now: Long
): RadarCountdown = when {
    isDeepSpace -> RadarCountdown("00:00:00", RadarPassPhase.DEEP_SPACE)
    now < aosTime -> RadarCountdown((aosTime - now).toTimerString(), RadarPassPhase.UPCOMING)
    now < losTime -> RadarCountdown((losTime - now).toTimerString(), RadarPassPhase.IN_PROGRESS)
    else -> RadarCountdown("00:00:00", RadarPassPhase.ENDED)
}
