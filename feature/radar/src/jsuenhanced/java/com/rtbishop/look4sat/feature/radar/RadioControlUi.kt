/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import java.math.BigDecimal
import java.util.Locale

internal enum class ReferenceTarget { RX, TX }

internal fun referenceFrequencyText(hz: Long): String = BigDecimal.valueOf(hz, 6).toPlainString()

internal fun parseReferenceFrequency(text: String, isL4s: Boolean): Long? = runCatching {
    text.toBigDecimal().movePointRight(6).longValueExact()
}.getOrNull()?.takeIf { it in if (isL4s) 18_000_000L..1_300_000_000L else 1L..9_999_999_999L }

internal data class ReferenceFrequencyDraft(val rx: String, val tx: String) {
    fun adjusted(target: ReferenceTarget, deltaHz: Long, isL4s: Boolean): ReferenceFrequencyDraft? {
        val current = parseReferenceFrequency(if (target == ReferenceTarget.RX) rx else tx, isL4s) ?: return null
        val adjusted = runCatching { referenceFrequencyText(Math.addExact(current, deltaHz)) }.getOrNull() ?: return null
        if (parseReferenceFrequency(adjusted, isL4s) == null) return null
        return if (target == ReferenceTarget.RX) copy(rx = adjusted) else copy(tx = adjusted)
    }

    fun bases(isL4s: Boolean, txEnabled: Boolean): RadioBaseFrequencies? {
        val rxHz = parseReferenceFrequency(rx, isL4s) ?: return null
        val txHz = if (txEnabled) parseReferenceFrequency(tx, isL4s) ?: return null else null
        return RadioBaseFrequencies(rxHz, txHz)
    }
}

internal fun referenceAdjustmentColumns(widthDp: Float, fontScale: Float): Int =
    if (widthDp / fontScale.coerceAtLeast(1f) < 280f) 2 else 3

internal fun stackRadioActionButtons(widthDp: Float, fontScale: Float): Boolean =
    widthDp / fontScale.coerceAtLeast(1f) < 300f

internal fun showTxToneControl(hasUplink: Boolean, currentMode: String?, transponderMode: String?): Boolean {
    val mode = currentMode?.takeIf { it.isNotBlank() } ?: transponderMode
    return hasUplink && mode?.trim()?.uppercase(Locale.US) in setOf("FM", "FMN", "FM-N", "NFM")
}
