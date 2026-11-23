package com.rtbishop.look4sat.core.domain.ft4

/** A CAT transaction must finish before the guard at the end of this window. */
data class Ft4CatWindow(val nextSlotUtcMillis: Long) {
    val startUtcMillis: Long get() = nextSlotUtcMillis - 1_500L
    val deadlineUtcMillis: Long get() = nextSlotUtcMillis - 160L
    val midpointUtcMillis: Long get() = nextSlotUtcMillis + 500L + 5_040L / 2

    fun remainingMillis(now: Long): Long =
        if (now in startUtcMillis until deadlineUtcMillis) deadlineUtcMillis - now else 0L

    companion object {
        fun following(now: Long): Ft4CatWindow =
            Ft4CatWindow((Math.floorDiv(now, 7_500L) + 1) * 7_500L)
    }
}
