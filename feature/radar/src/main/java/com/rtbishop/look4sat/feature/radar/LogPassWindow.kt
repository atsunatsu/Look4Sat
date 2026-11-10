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

/**
 * Check the QSO-eligible window supplied by the radar. For a matched pass this
 * is the simultaneous-visibility interval; for a normal radar pass it is the
 * station's local AOS/LOS interval.
 */
internal fun isOutsideLogPassWindow(
    timeMillis: Long,
    qsoWindow: ClosedRange<Long>?
): Boolean = qsoWindow != null && timeMillis !in qsoWindow
