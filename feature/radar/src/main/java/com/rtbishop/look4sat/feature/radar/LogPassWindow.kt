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
 * Check only the selected radar station's own AOS–LOS window.
 * The opposite station's visibility is intentionally irrelevant to log entry.
 */
internal fun isOutsideLogPassWindow(
    timeMillis: Long,
    localPassWindow: ClosedRange<Long>?
): Boolean = localPassWindow != null && timeMillis !in localPassWindow
