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
 * Log-window validation is based on the selected radar pass's observer-local
 * AOS/LOS. For matched radar this must not be replaced by the mutual overlap.
 */
internal fun isOutsideLogPassWindow(
    timeMillis: Long,
    localPassWindow: ClosedRange<Long>?
): Boolean = localPassWindow != null && timeMillis !in localPassWindow
